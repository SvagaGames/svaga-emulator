package com.svaga.emulator

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

data class Rom(
    val id: String,
    val title: String,
    val system: Console,
    val file: String,
    val gameId: Int? = null,
    val gameName: String? = null,
    val lastPlayed: Long = 0,
    val playSeconds: Long = 0,
)

class ImportResult(val added: List<Rom>, val skipped: Int)

/** ROMs live in filesDir/roms, saves in filesDir/saves, the index in filesDir/roms.json. Nothing leaves the device. */
class RomStore(private val context: Context) {
    private val romsDir = File(context.filesDir, "roms").apply { mkdirs() }
    val savesDir = File(context.filesDir, "saves").apply { mkdirs() }
    val systemDir = File(context.filesDir, "system").apply { mkdirs() }
    private val index = File(context.filesDir, "roms.json")

    @Synchronized
    fun all(): List<Rom> {
        if (!index.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val sys = Console.byName(o.optString("system")) ?: return@mapNotNull null
                Rom(
                    id = o.getString("id"), title = o.getString("title"), system = sys, file = o.getString("file"),
                    gameId = if (o.has("gameId")) o.getInt("gameId") else null,
                    gameName = o.optString("gameName").ifEmpty { null },
                    lastPlayed = o.optLong("lastPlayed"), playSeconds = o.optLong("playSeconds"),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun write(list: List<Rom>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().put("id", r.id).put("title", r.title).put("system", r.system.name).put("file", r.file)
                .put("lastPlayed", r.lastPlayed).put("playSeconds", r.playSeconds).apply {
                    r.gameId?.let { put("gameId", it) }
                    r.gameName?.let { put("gameName", it) }
                })
        }
        val tmp = File(index.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
    }

    fun path(rom: Rom): String = File(romsDir, rom.file).path

    fun update(id: String, change: (Rom) -> Rom) = synchronized(this) { write(all().map { if (it.id == id) change(it) else it }) }

    fun get(id: String): Rom? = all().firstOrNull { it.id == id }

    fun addPlaytime(id: String, seconds: Long) = update(id) { it.copy(playSeconds = it.playSeconds + seconds, lastPlayed = System.currentTimeMillis()) }

    fun findByGame(gameId: Int): Rom? = all().firstOrNull { it.gameId == gameId }

    fun delete(rom: Rom) {
        File(romsDir, rom.file).delete()
        savesDir.listFiles { f -> f.name.startsWith(rom.id) }?.forEach { it.delete() }
        synchronized(this) { write(all().filter { it.id != rom.id }) }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: (uri.lastPathSegment ?: "game")

    /** "Super Game (USA) [!].sfc" -> "Super Game" */
    private fun cleanTitle(fileName: String): String =
        fileName.substringBeforeLast('.').replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace('_', ' ').trim().ifEmpty { fileName }

    fun import(uris: List<Uri>): ImportResult {
        val added = mutableListOf<Rom>()
        var skipped = 0
        for (uri in uris) {
            val name = displayName(uri)
            val ext = name.substringAfterLast('.', "").lowercase()
            val rom = runCatching {
                if (ext == "zip") importZip(uri, name) else importPlain(uri, name, ext)
            }.getOrNull()
            if (rom != null) added += rom else skipped++
        }
        if (added.isNotEmpty()) synchronized(this) { write(all() + added) }
        return ImportResult(added, skipped)
    }

    private fun importPlain(uri: Uri, name: String, ext: String): Rom? {
        val sys = Console.byExt(ext) ?: return null
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val out = File(romsDir, "$id.$ext")
        context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } } ?: return null
        return Rom(id, cleanTitle(name), sys, out.name)
    }

    /** A zip with one ROM inside: the first entry with a known extension is extracted. */
    private fun importZip(uri: Uri, zipName: String): Rom? {
        // Arcade / Neo Geo sets are zips of many small files: the zip itself is the game.
        var entries = 0
        context.contentResolver.openInputStream(uri)?.use { raw -> ZipInputStream(raw).use { z -> while (z.nextEntry != null) entries++ } }
        if (entries > 3) {
            val id = UUID.randomUUID().toString().replace("-", "").take(12)
            val out = File(romsDir, "$id.zip")
            context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } } ?: return null
            return Rom(id, cleanTitle(zipName), Console.ARCADE, out.name)
        }
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: return null
                    if (e.isDirectory) continue
                    val ext = e.name.substringAfterLast('.', "").lowercase()
                    val sys = Console.byExt(ext) ?: continue
                    val id = UUID.randomUUID().toString().replace("-", "").take(12)
                    val out = File(romsDir, "$id.$ext")
                    out.outputStream().use { zin.copyTo(it) }
                    return Rom(id, cleanTitle(e.name.substringAfterLast('/').ifEmpty { zipName }), sys, out.name)
                }
            }
        }
        return null
    }

    /** Copies BIOS files into the emulator's system folder (Dreamcast files named dc_* go into system/dc). */
    fun importBios(uris: List<Uri>): Int {
        var n = 0
        for (uri in uris) {
            val name = displayName(uri)
            val dir = if (name.startsWith("dc_")) File(systemDir, "dc").apply { mkdirs() } else systemDir
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { i -> File(dir, name).outputStream().use { o -> i.copyTo(o) } }
                n++
            }
        }
        return n
    }
}
