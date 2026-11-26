package com.svaga.emulator

import android.content.Context
import java.io.File

/**
 * A supported system and the libretro core(s) that can run it. The core file is libNAME_libretro_android.so in jniLibs;
 * when several candidates are listed, the first one that is present in the APK is used.
 */
enum class Console(val label: String, val cores: List<String>, val exts: List<String>) {
    NES("NES", listOf("fceumm"), listOf("nes", "fds", "unf", "unif")),
    SNES("SNES", listOf("snes9x"), listOf("sfc", "smc", "swc", "fig")),
    MD("Mega Drive", listOf("genesis_plus_gx"), listOf("md", "gen", "smd", "bin")),
    SMS("Master System", listOf("genesis_plus_gx"), listOf("sms")),
    GG("Game Gear", listOf("genesis_plus_gx"), listOf("gg")),
    SEGACD("Sega CD", listOf("genesis_plus_gx"), listOf()),
    S32X("Sega 32X", listOf("picodrive"), listOf("32x")),
    GB("Game Boy", listOf("gambatte"), listOf("gb")),
    GBC("Game Boy Color", listOf("gambatte"), listOf("gbc")),
    GBA("GBA", listOf("mgba"), listOf("gba")),
    VB("Virtual Boy", listOf("mednafen_vb"), listOf("vb", "vboy")),
    NDS("Nintendo DS", listOf("melondsds", "melonds", "desmume"), listOf("nds")),
    N64("Nintendo 64", listOf("mupen64plus_next_gles3", "mupen64plus_next"), listOf("z64", "n64", "v64")),
    PCE("PC Engine", listOf("mednafen_pce_fast"), listOf("pce", "sgx")),
    PS1("PlayStation", listOf("pcsx_rearmed", "swanstation"), listOf("chd", "pbp", "iso", "img", "cue")),
    PSP("PSP", listOf("ppsspp"), listOf("cso")),
    DC("Dreamcast", listOf("flycast"), listOf("gdi", "cdi")),
    SATURN("Saturn", listOf("yabasanshiro"), listOf()),
    PS3DO("3DO", listOf("opera"), listOf()),
    A2600("Atari 2600", listOf("stella", "stella2014"), listOf("a26")),
    A5200("Atari 5200", listOf("a5200"), listOf("a52")),
    A7800("Atari 7800", listOf("prosystem"), listOf("a78")),
    LYNX("Atari Lynx", listOf("handy"), listOf("lnx")),
    JAGUAR("Atari Jaguar", listOf("virtualjaguar"), listOf("j64", "jag")),
    NGP("Neo Geo Pocket", listOf("mednafen_ngp"), listOf("ngp", "ngc")),
    WS("WonderSwan", listOf("mednafen_wswan"), listOf("ws", "wsc")),
    INTV("Intellivision", listOf("freeintv"), listOf("int")),
    COLECO("ColecoVision / MSX", listOf("bluemsx"), listOf("col", "mx1", "mx2")),
    O2("Odyssey 2", listOf("o2em"), listOf("o2")),
    VECTREX("Vectrex", listOf("vecx"), listOf("vec")),
    MINI("Pokémon Mini", listOf("pokemini"), listOf("min")),
    ARCADE("Arcade / Neo Geo", listOf("fbneo", "mame2003_plus"), listOf()),
    DOOM("Doom", listOf("prboom"), listOf("wad"));

    private fun file(context: Context, core: String) = File(context.applicationInfo.nativeLibraryDir + "/lib${core}_libretro_android.so")

    /** Path of the first core of this system that is present in the installed APK, or null. */
    fun corePath(context: Context): String? = cores.map { file(context, it) }.firstOrNull { it.exists() }?.path

    fun coreInstalled(context: Context): Boolean = corePath(context) != null

    /** Systems with two screens where the picture itself is the touch surface. */
    val touchScreen: Boolean get() = this == NDS
    /** Touch overlay uses an analog stick instead of a d-pad. */
    val analog: Boolean get() = this == N64 || this == DC
    /** Hardware gamepad sticks are sent as analog. */
    val hwAnalog: Boolean get() = analog || this == PSP || this == PS1 || this == SATURN

    companion object {
        fun byExt(ext: String): Console? = entries.firstOrNull { ext.lowercase() in it.exts }
        fun byName(name: String?): Console? = entries.firstOrNull { it.name == name }
        val allExts: List<String> get() = entries.flatMap { it.exts } + "zip"
        /** Systems that can be picked by hand when the file extension is ambiguous (iso, chd, bin, zip ...). */
        val chooser: List<Console> get() = entries
    }
}
