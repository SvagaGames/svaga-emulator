package com.svaga.emulator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Self-update from GitHub releases of SvagaGames/svaga-emulator (same key, so it installs over the current version). */
object EmuUpdate {
    var version by mutableStateOf<String?>(null)
    var notes by mutableStateOf<String?>(null)
    var progress by mutableStateOf<Float?>(null)
    var file by mutableStateOf<File?>(null)
    var checking by mutableStateOf(false)
    var upToDate by mutableStateOf<Boolean?>(null)
    var failed by mutableStateOf(false)
    var dismissed by mutableStateOf(false)
    private var url: String? = null

    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The hidden build number decides (all builds are named 1.0); the name is compared only for old releases without one. */
    private fun newerBuild(v: String, code: Int?) = if (code != null) code > BuildConfig.VERSION_CODE else isNewer(v, BuildConfig.VERSION_NAME)

    fun check(ctx: Context, manual: Boolean) {
        val prefs = ctx.getSharedPreferences("svaga_updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("last_check", 0) < 6 * 3600 * 1000L) {
            val v = prefs.getString("version", null)
            val u = prefs.getString("url", null)
            if (v != null && u != null && newerBuild(v, prefs.getInt("code", -1).takeIf { it > 0 })) { version = v; url = u }
            return
        }
        checking = true
        upToDate = null
        failed = false
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val o = withContext(Dispatchers.IO) {
                    val c = URL("https://api.github.com/repos/SvagaGames/svaga-emulator/releases/latest").openConnection() as HttpURLConnection
                    c.setRequestProperty("Accept", "application/vnd.github+json")
                    c.connectTimeout = 15000; c.readTimeout = 20000
                    if (c.responseCode !in 200..299) throw java.io.IOException("http ${c.responseCode}")
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                }
                val v = o.optString("tag_name").removePrefix("v")
                val assets = o.optJSONArray("assets")
                var u: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == "SvagaEmulator.apk") u = a.optString("browser_download_url")
                }
                val body = o.optString("body")
                val code = Regex("<!--\\s*vc:(\\d+)\\s*-->").find(body)?.groupValues?.get(1)?.toIntOrNull()
                prefs.edit().putLong("last_check", now).putString("version", v).putString("url", u).putInt("code", code ?: -1).apply()
                if (u != null && v.isNotBlank() && newerBuild(v, code)) {
                    version = v; url = u; notes = body.replace(Regex("<!--.*?-->"), "").trim().ifBlank { null }; dismissed = false; upToDate = false
                } else {
                    version = null; upToDate = true
                }
            } catch (e: Exception) {
                if (manual) failed = true
            } finally {
                checking = false
            }
        }
    }

    fun start(activity: Activity, net: Net) {
        val u = url ?: return
        if (progress != null) return
        progress = 0f
        failed = false
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, "SvagaEmulator-${version ?: "new"}.apk")
                net.download(u, f) { p -> activity.runOnUiThread { progress = p } }
                file = f
                progress = null
                install(activity)
            } catch (e: Exception) {
                progress = null
                failed = true
            }
        }
    }

    fun install(activity: Activity) {
        val f = file ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { failed = true }
    }
}
