package com.svaga.emulator

import android.content.Context

/**
 * Links imported ROMs to games of the Svaga catalog by their title, so the time played goes to Svaga Archive
 * (hours, levels and achievements) without any setup. Each ROM is tried once; a wrong guess can be relinked by hand.
 */
object AutoLink {
    private val IGDB = mapOf(
        Console.NES to listOf("nintendo entertainment system", "famicom"),
        Console.SNES to listOf("super nintendo", "super famicom"),
        Console.MD to listOf("mega drive", "genesis"),
        Console.SMS to listOf("master system"),
        Console.GG to listOf("game gear"),
        Console.SEGACD to listOf("sega cd", "mega-cd"),
        Console.S32X to listOf("32x"),
        Console.GB to listOf("game boy"),
        Console.GBC to listOf("game boy color", "game boy"),
        Console.GBA to listOf("game boy advance"),
        Console.VB to listOf("virtual boy"),
        Console.NDS to listOf("nintendo ds"),
        Console.N64 to listOf("nintendo 64"),
        Console.PCE to listOf("turbografx", "pc engine"),
        Console.PS1 to listOf("playstation"),
        Console.PSP to listOf("playstation portable"),
        Console.DC to listOf("dreamcast"),
        Console.SATURN to listOf("saturn"),
        Console.PS3DO to listOf("3do"),
        Console.A2600 to listOf("atari 2600"),
        Console.A7800 to listOf("atari 7800"),
        Console.LYNX to listOf("lynx"),
        Console.JAGUAR to listOf("jaguar"),
        Console.NGP to listOf("neo geo pocket"),
        Console.WS to listOf("wonderswan"),
    )

    fun norm(s: String) = s.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .replace(Regex("^the |, the\\b"), " ")
        .replace('&', ' ').replace(Regex("\\band\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /** Best catalog game for a ROM, or null if nothing matches well enough. */
    fun pick(rom: Rom, found: List<FoundGame>): FoundGame? {
        val title = norm(rom.title)
        if (title.length < 2) return null
        val same = found.filter { norm(it.name) == title }
        if (same.isEmpty()) return null
        val keys = IGDB[rom.system].orEmpty()
        val onPlatform = same.filter { g -> keys.any { k -> g.platforms.lowercase().contains(k) } }
        return onPlatform.firstOrNull() ?: same.singleOrNull()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("svaga_autolink", Context.MODE_PRIVATE)
    fun tried(ctx: Context, romId: String) = prefs(ctx).getBoolean(romId, false)
    fun markTried(ctx: Context, romId: String) = prefs(ctx).edit().putBoolean(romId, true).apply()

    /** Links up to [max] not yet tried ROMs. Returns how many were linked. */
    suspend fun run(ctx: Context, store: RomStore, net: Net, max: Int = 15): Int {
        var linked = 0
        store.all().filter { it.gameId == null && !tried(ctx, it.id) && it.system != Console.DOOM }.take(max).forEach { r ->
            val found = runCatching { net.search(r.title) }.getOrNull() ?: return linked // offline: try later
            markTried(ctx, r.id)
            val g = pick(r, found) ?: return@forEach
            store.update(r.id) { it.copy(gameId = g.id, gameName = g.name, cover = g.cover ?: it.cover) }
            linked++
        }
        return linked
    }
}
