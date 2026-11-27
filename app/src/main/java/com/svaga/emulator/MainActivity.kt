package com.svaga.emulator

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    private lateinit var store: RomStore
    private lateinit var session: Session
    private lateinit var net: Net

    // A game requested by Svaga Archive that has no ROM yet: the next import is linked to it.
    private var pendingGameId by mutableStateOf<Int?>(null)
    private var pendingGameName by mutableStateOf<String?>(null)
    private var roms by mutableStateOf<List<Rom>>(emptyList())
    private var signedIn by mutableStateOf(false)
    private var svagaList by mutableStateOf<List<SvagaRelease>>(emptyList())
    private var pendingRelease by mutableStateOf<SvagaRelease?>(null)
    private var downloads by mutableStateOf<Map<Int, Float>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = RomStore(this)
        session = Session(this)
        net = Net(session)
        roms = store.all()
        signedIn = session.signedIn
        if (savedInstanceState == null) handleIntent(intent)
        EmuUpdate.check(this, manual = false)
        setContent { SvagaTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        roms = store.all()
        if (session.signedIn) CoroutineScope(Dispatchers.IO).launch { PlaytimeQueue(this@MainActivity).flush(net) }
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action != "com.svaga.emulator.PLAY") return
        val gameId = i.getIntExtra("gameId", -1).takeIf { it > 0 } ?: return
        val name = i.getStringExtra("gameName") ?: ""
        val rom = store.findByGame(gameId)
        if (rom != null) {
            play(rom)
        } else {
            pendingGameId = gameId
            pendingGameName = name
        }
    }

    private fun play(rom: Rom) {
        if (!rom.system.coreInstalled(this)) {
            Toast.makeText(this, R.string.core_missing, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("romId", rom.id))
    }

    /** Downloads the file the Creator attached to a Svaga game, imports it and links it to that game. */
    private fun downloadRelease(r: SvagaRelease) {
        val url = r.url ?: return
        if (downloads.containsKey(r.gameId)) return
        downloads = downloads + (r.gameId to 0f)
        CoroutineScope(Dispatchers.Main).launch {
            val dir = java.io.File(cacheDir, "downloads").apply { mkdirs() }
            val name = (r.fileName ?: url.substringAfterLast('/').substringBefore('?')).replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "game.bin" }
            val file = java.io.File(dir, name)
            try {
                net.download(url, file) { p -> runOnUiThread { downloads = downloads + (r.gameId to p) } }
                val res = withContext(Dispatchers.IO) { store.import(listOf(android.net.Uri.fromFile(file))) }
                val rom = res.added.firstOrNull()
                if (rom == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.download_unknown_format, name), Toast.LENGTH_LONG).show()
                } else {
                    store.update(rom.id) { it.copy(gameId = r.gameId, gameName = r.name, cover = r.cover, title = r.name) }
                    if (pendingGameId == r.gameId) { pendingGameId = null; pendingGameName = null; pendingRelease = null }
                    Toast.makeText(this@MainActivity, getString(R.string.download_done, r.name), Toast.LENGTH_LONG).show()
                }
                roms = store.all()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.download_failed, Toast.LENGTH_LONG).show()
            } finally {
                file.delete()
                downloads = downloads - r.gameId
            }
        }
    }

    private fun fmtDur(seconds: Long): String {
        val m = (seconds / 60).toInt()
        return if (m < 60) getString(R.string.playtime_min, m) else getString(R.string.playtime_h, m / 60, m % 60)
    }

    // =================================================================== root, tabs

    @Composable
    private fun Root() {
        var tab by remember { mutableIntStateOf(0) }
        var systemFilter by remember { mutableStateOf<Console?>(null) }
        BackHandler(enabled = tab != 0) { tab = 0 }
        Box(Modifier.fillMaxSize().background(Bg)) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                when (tab) {
                    0 -> LibraryTab(systemFilter) { systemFilter = it }
                    1 -> SystemsTab { c -> systemFilter = c; tab = 0 }
                    2 -> PlayersTab()
                    else -> ProfileTab()
                }
            }
            BottomBar(tab, Modifier.align(Alignment.BottomCenter)) { tab = it }
        }
    }

    @Composable
    private fun BottomBar(tab: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
        val items = listOf("🎮" to R.string.tab_games, "🕹" to R.string.tab_systems, "👥" to R.string.players, "☺" to R.string.tab_profile)
        val shape = RoundedCornerShape(34.dp)
        Row(
            modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth()
                .shadow(16.dp, shape).clip(shape).background(Panel.copy(alpha = 0.97f), shape).border(1.dp, Line, shape).padding(6.dp),
        ) {
            items.forEachIndexed { i, (icon, label) ->
                val sel = i == tab
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(28.dp)).background(if (sel) Panel2 else Color.Transparent)
                        .clickable { onSelect(i) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(icon, fontSize = 20.sp, color = if (sel) Cyan else Muted)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(label), color = if (sel) Ink else Muted, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }

    // =================================================================== library

    @Composable
    private fun LibraryTab(filter: Console?, onFilter: (Console?) -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var selected by remember { mutableStateOf<Rom?>(null) }
        var query by remember { mutableStateOf("") }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                val res = withContext(Dispatchers.IO) { store.import(uris) }
                val gid = pendingGameId
                if (gid != null && res.added.size == 1) {
                    store.update(res.added[0].id) { it.copy(gameId = gid, gameName = pendingGameName) }
                    pendingGameId = null; pendingGameName = null
                }
                roms = store.all()
                val msg = buildString {
                    append(ctx.getString(R.string.imported_n, res.added.size))
                    if (res.skipped > 0) append(" · ").append(ctx.getString(R.string.skipped_n, res.skipped))
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
        LaunchedEffect(signedIn) {
            if (signedIn) svagaList = runCatching { net.svagaGames() }.getOrDefault(svagaList)
        }
        LaunchedEffect(pendingGameId, signedIn) {
            val gid = pendingGameId
            pendingRelease = if (gid != null && signedIn) runCatching { net.svagaGame(gid) }.getOrNull() else null
        }
        // new ROMs are linked to the Svaga catalog by title: their play time then goes to Svaga Archive
        LaunchedEffect(roms.size, signedIn) {
            if (!signedIn) return@LaunchedEffect
            val n = withContext(Dispatchers.IO) { AutoLink.run(ctx, store, net) }
            if (n > 0) {
                roms = store.all()
                Toast.makeText(ctx, ctx.getString(R.string.autolinked_n, n), Toast.LENGTH_LONG).show()
            }
        }
        // linked ROMs get their cover from the Svaga catalog once
        LaunchedEffect(roms, signedIn) {
            if (!signedIn) return@LaunchedEffect
            roms.filter { it.gameId != null && it.cover == null }.take(20).forEach { r ->
                val c = runCatching { net.gameCover(r.gameId!!) }.getOrNull()
                if (c != null) store.update(r.id) { it.copy(cover = c) }
            }
            if (roms.any { it.gameId != null && it.cover == null }) roms = store.all()
        }

        val systems = roms.groupBy { it.system }
        val shown = roms
            .filter { filter == null || it.system == filter }
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareByDescending<Rom> { it.lastPlayed }.thenByDescending { it.addedAt }.thenBy { it.title })
        val last = roms.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }

        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Logo(Modifier.weight(1f))
                            Text(
                                stringResource(R.string.import_roms_short), color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(50))
                                    .clickable { picker.launch(arrayOf("*/*")) }.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                        UpdateBanner()
                        pendingGameName?.let { name ->
                            val rel = pendingRelease
                            SvCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), border = Amber.copy(alpha = 0.6f)) {
                                Body(stringResource(if (rel?.url != null) R.string.launch_can_download else R.string.launch_missing, name), color = Amber, size = 14)
                                downloads[pendingGameId]?.let { p -> Spacer(Modifier.height(8.dp)); ProgressLine(p, Cyan) }
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (rel?.url != null) {
                                        SvButton(stringResource(R.string.download_game), { downloadRelease(rel) }, Modifier.weight(1f), busy = downloads.containsKey(rel.gameId))
                                    } else {
                                        SvButton(stringResource(R.string.import_roms), { picker.launch(arrayOf("*/*")) }, Modifier.weight(1f))
                                    }
                                    SvButton(stringResource(R.string.cancel), { pendingGameId = null; pendingGameName = null; pendingRelease = null }, kind = BtnKind.Secondary)
                                }
                            }
                        }
                        val ready = svagaList.filter { it.url != null }
                        if (ready.isNotEmpty() && query.isBlank() && filter == null) {
                            Text(stringResource(R.string.svaga_shelf), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ready.forEach { r -> ReleaseCard(r) }
                            }
                        }
                        if (last != null && filter == null && query.isBlank()) {
                            ContinueCard(last, onPlay = { play(last) }, onMore = { selected = last })
                            Spacer(Modifier.height(16.dp))
                        }
                        if (roms.isNotEmpty()) {
                            SearchField(query) { query = it }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SvChip(stringResource(R.string.all_systems), filter == null, { onFilter(null) }, count = roms.size)
                                systems.keys.sortedBy { it.ordinal }.forEach { c ->
                                    SvChip(c.label, filter == c, { onFilter(if (filter == c) null else c) }, count = systems[c]?.size)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                if (roms.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary { picker.launch(arrayOf("*/*")) } }
                } else if (shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Caption(stringResource(R.string.not_found), Modifier.padding(16.dp)) }
                }
                items(shown, key = { it.id }) { rom -> RomCard(rom, onPlay = { play(rom) }, onMore = { selected = rom }) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Caption(stringResource(R.string.legal), color = Faint, maxLines = 6)
                        Caption(stringResource(R.string.version), color = Faint)
                    }
                }
            }
        }

        selected?.let { rom ->
            RomSheet(
                store.get(rom.id) ?: rom,
                onClose = { selected = null },
                onChanged = { roms = store.all(); selected = store.get(rom.id) },
                onPlay = { selected = null; play(rom) },
                onDelete = { store.delete(rom); roms = store.all(); selected = null },
            )
        }
    }

    @Composable
    private fun UpdateBanner() {
        val v = EmuUpdate.version
        if ((v == null && EmuUpdate.file == null) || EmuUpdate.dismissed) return
        SvCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), border = Cyan.copy(alpha = 0.6f)) {
            val p = EmuUpdate.progress
            Body(
                when {
                    EmuUpdate.file != null -> stringResource(R.string.update_ready)
                    p != null -> stringResource(R.string.update_downloading, (p * 100).toInt())
                    else -> stringResource(R.string.update_available_v, v ?: "")
                },
                bold = true,
            )
            if (p != null) { Spacer(Modifier.height(8.dp)); ProgressLine(p, Cyan) }
            if (EmuUpdate.failed) Caption(stringResource(R.string.update_failed), color = Red)
            if (p == null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (EmuUpdate.file != null) SvButton(stringResource(R.string.update_install), { EmuUpdate.install(this@MainActivity) }, Modifier.weight(1f))
                    else SvButton(stringResource(R.string.update_get), { EmuUpdate.start(this@MainActivity, net) }, Modifier.weight(1f))
                    SvButton(stringResource(R.string.later), { EmuUpdate.dismissed = true }, kind = BtnKind.Secondary)
                }
            }
        }
    }

    @Composable
    private fun ReleaseCard(r: SvagaRelease) {
        val have = roms.firstOrNull { it.gameId == r.gameId }
        val p = downloads[r.gameId]
        Column(Modifier.width(118.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(CoverShape).background(Brush.linearGradient(listOf(Cyan.copy(alpha = 0.4f), Blue.copy(alpha = 0.4f))))) {
                if (r.cover != null) AsyncImage(r.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Pill("SVAGA", Green, Modifier.align(Alignment.TopStart).padding(6.dp), filled = true)
            }
            Spacer(Modifier.height(6.dp))
            Body(r.name, size = 13, maxLines = 2, bold = true)
            Spacer(Modifier.height(6.dp))
            when {
                p != null -> ProgressLine(p, Cyan)
                have != null -> PixelButton("▶ " + stringResource(R.string.play), { play(have) }, Modifier.fillMaxWidth())
                else -> PixelButton(stringResource(R.string.download_short), { downloadRelease(r) }, Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun SearchField(value: String, onChange: (String) -> Unit) {
        val shape = RoundedCornerShape(50)
        Row(Modifier.fillMaxWidth().clip(shape).background(Panel, shape).border(1.dp, Line, shape).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⌕", color = Muted, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(stringResource(R.string.search_library), color = Faint, fontSize = 15.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value, onChange, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 15.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Cyan), modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) Text("✕", color = Muted, fontSize = 15.sp, modifier = Modifier.clickable { onChange("") }.padding(start = 8.dp))
        }
    }

    @Composable
    private fun EmptyLibrary(onImport: () -> Unit) {
        SvCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("🎮", fontSize = 44.sp)
            Spacer(Modifier.height(8.dp))
            H2(stringResource(R.string.empty_title))
            Spacer(Modifier.height(8.dp))
            Body(stringResource(R.string.empty_library), color = Muted, size = 14)
            Spacer(Modifier.height(16.dp))
            SvButton(stringResource(R.string.import_roms), onImport, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Caption(stringResource(R.string.empty_bios_hint), color = Faint, maxLines = 4)
        }
    }

    @Composable
    private fun ContinueCard(rom: Rom, onPlay: () -> Unit, onMore: () -> Unit) {
        val shape = CardShape
        Box(Modifier.fillMaxWidth().height(170.dp).clip(shape).background(Panel, shape).border(1.dp, Line, shape).clickable(onClick = onMore)) {
            RomCover(rom, Modifier.fillMaxSize(), RoundedCornerShape(0.dp), showLabel = false)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20A0C11), Color(0xCC0A0C11), Color(0x330A0C11)))))
            Column(Modifier.fillMaxHeight().fillMaxWidth(0.7f).padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(stringResource(R.string.continue_title).uppercase(), color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(rom.title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, lineHeight = 24.sp)
                    Caption("${rom.system.label} · ${fmtDur(rom.playSeconds)}", maxLines = 1)
                }
                SvButton("▶  " + stringResource(R.string.play), onPlay, Modifier.width(150.dp))
            }
        }
    }

    @Composable
    private fun RomCard(rom: Rom, onPlay: () -> Unit, onMore: () -> Unit) {
        Column(Modifier.clip(CoverShape).clickable(onClick = onMore)) {
            Box {
                RomCover(rom, Modifier.fillMaxWidth().aspectRatio(0.75f), CoverShape)
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).size(34.dp).clip(CircleShape).background(AccentBrush).clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) { Text("▶", color = Color(0xFF071018), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                if (rom.gameId != null) Pill("SVAGA", Green, Modifier.align(Alignment.TopStart).padding(6.dp), filled = true)
            }
            Spacer(Modifier.height(6.dp))
            Body(rom.title, size = 13, maxLines = 2, bold = true)
            Caption(rom.system.label + if (rom.playSeconds >= 60) " · " + fmtDur(rom.playSeconds) else "", maxLines = 1)
        }
    }

    // =================================================================== ROM sheet

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RomSheet(rom: Rom, onClose: () -> Unit, onChanged: () -> Unit, onPlay: () -> Unit, onDelete: () -> Unit) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var confirmDelete by remember { mutableStateOf(false) }
        var mode by remember { mutableStateOf("main") }
        var query by remember { mutableStateOf(rom.title) }
        var results by remember { mutableStateOf<List<FoundGame>?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var community by remember { mutableStateOf<Community?>(null) }
        var myRating by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(rom.gameId, signedIn) {
            val gid = rom.gameId
            community = if (gid != null && signedIn) runCatching { net.community(gid) }.getOrNull() else null
        }
        ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = Bg) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    RomCover(rom, Modifier.width(96.dp).aspectRatio(0.75f), CoverShape)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(rom.title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp, maxLines = 3)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(rom.system.label, Cyan)
                            if (rom.gameName != null) Pill("Svaga", Green)
                        }
                        Spacer(Modifier.height(8.dp))
                        Caption(if (rom.playSeconds < 30) stringResource(R.string.never_played) else stringResource(R.string.played_total, fmtDur(rom.playSeconds)))
                        rom.gameName?.let { Caption(stringResource(R.string.linked_with, it), color = Green, maxLines = 1) }
                        if (rom.gameName == null) Caption(stringResource(R.string.not_linked_hint), color = Amber, maxLines = 3)
                    }
                }
                Spacer(Modifier.height(16.dp))
                SvButton("▶  " + stringResource(R.string.play), onPlay, Modifier.fillMaxWidth())

                community?.let { c ->
                    Spacer(Modifier.height(16.dp))
                    SvCard(Modifier.fillMaxWidth(), border = Amber.copy(alpha = 0.4f)) {
                        Text(stringResource(R.string.community), color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Body((c.avgRating?.let { "★ $it (${c.ratingCount})  ·  " } ?: "") + stringResource(R.string.community_total, fmtDur(c.totalSeconds), c.players), size = 14)
                        c.top.take(5).forEachIndexed { i, p ->
                            Caption("${i + 1}. ${p.name} — ${fmtDur(p.seconds)}" + (p.rating?.let { "  ★$it" } ?: ""), maxLines = 1)
                        }
                        Spacer(Modifier.height(10.dp))
                        Caption(stringResource(R.string.rate_game))
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..10).forEach { n ->
                                val on = myRating == n
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).background(if (on) Amber else Panel2).border(1.dp, Amber.copy(alpha = 0.6f), CircleShape)
                                        .clickable {
                                            myRating = n
                                            scope.launch { runCatching { net.rate(rom.gameId!!, n); community = net.community(rom.gameId!!) } }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) { Text("$n", color = if (on) Bg else Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                when (mode) {
                    "system" -> {
                        SectionLabel(stringResource(R.string.choose_system))
                        Console.chooser.forEach { c ->
                            val ok = c.coreInstalled(ctx)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (c == rom.system) Cyan.copy(alpha = 0.16f) else Color.Transparent)
                                    .clickable(enabled = ok) { store.update(rom.id) { it.copy(system = c) }; mode = "main"; onChanged() }
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                            ) {
                                Body(c.label, Modifier.weight(1f), color = if (c == rom.system) Cyan else if (ok) Ink else Faint)
                                if (!ok) Caption(stringResource(R.string.core_not_in_build), color = Faint)
                            }
                        }
                    }
                    "link" -> {
                        SvField(query, { query = it }, stringResource(R.string.search_hint))
                        Spacer(Modifier.height(10.dp))
                        SvButton(stringResource(R.string.search), {
                            busy = true; error = null
                            scope.launch {
                                try { results = net.search(query.trim()) } catch (e: ApiError) { error = e.code } catch (e: Exception) { error = "net" }
                                busy = false
                            }
                        }, Modifier.fillMaxWidth(), enabled = query.isNotBlank(), busy = busy)
                        error?.let { Caption(if (it == "net") stringResource(R.string.no_connection) else it, color = Red) }
                        results?.let { list ->
                            if (list.isEmpty()) Caption(stringResource(R.string.not_found), Modifier.padding(top = 10.dp))
                            list.take(10).forEach { g ->
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(Panel).clickable {
                                        store.update(rom.id) { it.copy(gameId = g.id, gameName = g.name, cover = g.cover) }
                                        mode = "main"; results = null; onChanged()
                                    }.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.width(40.dp).aspectRatio(0.75f).clip(RoundedCornerShape(8.dp)).background(Panel2)) {
                                        if (g.cover != null) AsyncImage(g.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Body(g.name + (g.year?.let { " ($it)" } ?: ""), size = 14, bold = true, maxLines = 2)
                                        if (g.platforms.isNotEmpty()) Caption(g.platforms, maxLines = 1)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        SvButton(stringResource(R.string.cancel), { mode = "main" }, Modifier.fillMaxWidth(), kind = BtnKind.Secondary)
                    }
                    else -> {
                        SvCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                            ActionRow("🕹", stringResource(R.string.change_system, rom.system.label)) { mode = "system" }
                            ActionRow("🔗", stringResource(if (rom.gameId == null) R.string.link_to_svaga else R.string.relink)) {
                                if (signedIn) mode = "link" else Toast.makeText(ctx, R.string.need_login, Toast.LENGTH_LONG).show()
                            }
                            if (rom.gameId != null) ActionRow("⛓", stringResource(R.string.unlink)) {
                                store.update(rom.id) { it.copy(gameId = null, gameName = null, cover = null) }; onChanged()
                            }
                            ActionRow("🗑", stringResource(R.string.delete), Red) { confirmDelete = true }
                        }
                    }
                }
            }
        }
        if (confirmDelete) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmDelete = false },
                containerColor = Panel,
                shape = CardShape,
                text = { Body(stringResource(R.string.delete_confirm, rom.title)) },
                confirmButton = { androidx.compose.material3.TextButton({ confirmDelete = false; onDelete() }) { Text(stringResource(R.string.delete), color = Red, fontWeight = FontWeight.Bold) } },
                dismissButton = { androidx.compose.material3.TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel), color = Muted) } },
            )
        }
    }

    @Composable
    private fun ActionRow(icon: String, title: String, tint: Color = Ink, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(Panel2, CircleShape), contentAlignment = Alignment.Center) { Text(icon, fontSize = 16.sp) }
            Spacer(Modifier.width(14.dp))
            Body(title, Modifier.weight(1f), color = tint, bold = true, maxLines = 2)
            Text("›", color = Muted, fontSize = 22.sp)
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(text.uppercase(), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 8.dp))
    }

    // =================================================================== systems

    @Composable
    private fun SystemsTab(onOpen: (Console) -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val biosPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                val n = withContext(Dispatchers.IO) { store.importBios(uris) }
                Toast.makeText(ctx, ctx.getString(R.string.bios_added_n, n), Toast.LENGTH_LONG).show()
            }
        }
        val counts = roms.groupingBy { it.system }.eachCount()
        val installed = Console.entries.filter { it.coreInstalled(ctx) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    H1(stringResource(R.string.tab_systems))
                    Caption(stringResource(R.string.systems_subtitle, installed.size))
                }
                SvCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Body(stringResource(R.string.bios_title), bold = true)
                    Spacer(Modifier.height(4.dp))
                    Caption(stringResource(R.string.bios_hint), maxLines = 6)
                    Spacer(Modifier.height(12.dp))
                    SvButton(stringResource(R.string.import_bios_long), { biosPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), kind = BtnKind.Secondary)
                }
                Spacer(Modifier.height(8.dp))
            }
            items(Console.entries.toList(), key = { it.name }) { c ->
                val ok = c.coreInstalled(ctx)
                val n = counts[c] ?: 0
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = n > 0) { onOpen(c) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(systemColors(c))),
                        contentAlignment = Alignment.Center,
                    ) { Text(c.label.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Body(c.label, bold = true, maxLines = 1)
                        Caption(
                            (if (c.exts.isNotEmpty()) c.exts.joinToString(" ") { ".$it" } else stringResource(R.string.ext_choose)) +
                                (if (!ok) " · " + stringResource(R.string.core_not_in_build) else ""),
                            maxLines = 1, color = if (ok) Muted else Faint,
                        )
                    }
                    if (n > 0) Pill(n.toString(), Cyan)
                }
            }
        }
    }

    // =================================================================== players

    @Composable
    private fun PlayersTab() {
        var query by remember { mutableStateOf("") }
        var people by remember { mutableStateOf<List<Person>?>(null) }
        var error by remember { mutableStateOf(false) }
        LaunchedEffect(query, signedIn) {
            if (!signedIn) return@LaunchedEffect
            delay(300)
            error = false
            try { people = net.users(query.trim()) } catch (e: Exception) { error = true }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    H1(stringResource(R.string.players))
                    Caption(stringResource(R.string.players_subtitle))
                    Spacer(Modifier.height(12.dp))
                    if (signedIn) SearchField(query) { query = it }
                }
            }
            if (!signedIn) {
                item { SvCard(Modifier.fillMaxWidth().padding(16.dp)) { Body(stringResource(R.string.need_login), color = Muted, size = 14) } }
            } else {
                if (error) item { Caption(stringResource(R.string.no_connection), Modifier.padding(16.dp), color = Red) }
                val list = people.orEmpty()
                if (people != null && list.isEmpty() && !error) item { Caption(stringResource(R.string.not_found), Modifier.padding(16.dp)) }
                items(list, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Panel2).border(2.dp, AccentBrush, CircleShape), contentAlignment = Alignment.Center) {
                            Text(p.name.take(1).uppercase(), color = Cyan, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Body(p.name, bold = true, maxLines = 1)
                            Caption(stringResource(R.string.person_line, p.level, p.library, p.completed), maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    // =================================================================== profile / account

    @Composable
    private fun ProfileTab() {
        var email by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val ctx = LocalContext.current
        val failed = stringResource(R.string.login_failed)
        val noNet = stringResource(R.string.no_connection)
        val total = roms.sumOf { it.playSeconds }
        val archiveAuth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val d = res.data
            val access = d?.getStringExtra("accessToken")
            val refresh = d?.getStringExtra("refreshToken")
            if (res.resultCode == RESULT_OK && !access.isNullOrBlank() && !refresh.isNullOrBlank()) {
                net.saveHandoff(access, refresh, d!!.getStringExtra("email"))
                signedIn = true
                Toast.makeText(ctx, getString(R.string.signed_as, d.getStringExtra("displayName")?.ifBlank { null } ?: d.getStringExtra("email") ?: ""), Toast.LENGTH_LONG).show()
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 120.dp)) {
            H1(stringResource(R.string.tab_profile))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(roms.size.toString(), stringResource(R.string.stat_games), Modifier.weight(1f))
                StatTile((total / 3600).toString(), stringResource(R.string.stat_hours), Modifier.weight(1f), Cyan)
                StatTile(roms.map { it.system }.distinct().size.toString(), stringResource(R.string.stat_systems), Modifier.weight(1f), Amber)
            }
            Spacer(Modifier.height(16.dp))
            SvCard(Modifier.fillMaxWidth()) {
                Body(stringResource(R.string.account), bold = true)
                Spacer(Modifier.height(4.dp))
                if (signedIn) {
                    Caption(stringResource(R.string.signed_as, session.email ?: ""))
                    Spacer(Modifier.height(6.dp))
                    Caption(stringResource(R.string.account_benefits), color = Faint, maxLines = 4)
                    Spacer(Modifier.height(12.dp))
                    SvButton(stringResource(R.string.sign_out), { session.clear(); signedIn = false }, Modifier.fillMaxWidth(), kind = BtnKind.Danger)
                } else {
                    Caption(stringResource(R.string.account_benefits), maxLines = 4)
                    Spacer(Modifier.height(12.dp))
                    SvButton(stringResource(R.string.signin_with_archive), {
                        val i = Intent("com.svaga.archive.AUTHORIZE_EMULATOR").setPackage("com.svaga.archive")
                        try { archiveAuth.launch(i) } catch (e: Exception) {
                            Toast.makeText(ctx, R.string.archive_missing, Toast.LENGTH_LONG).show()
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://svagagames.github.io/svaga-archive/"))) }
                        }
                    }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(14.dp))
                    Caption(stringResource(R.string.or_password), color = Faint)
                    Spacer(Modifier.height(8.dp))
                    SvField(email, { email = it }, stringResource(R.string.email))
                    Spacer(Modifier.height(10.dp))
                    SvField(pass, { pass = it }, stringResource(R.string.password), password = true)
                    error?.let { Spacer(Modifier.height(6.dp)); Caption(it, color = Red) }
                    Spacer(Modifier.height(12.dp))
                    SvButton(stringResource(R.string.sign_in), {
                        busy = true; error = null
                        scope.launch {
                            try { net.login(email.trim(), pass); signedIn = true; pass = "" } catch (e: ApiError) { error = failed } catch (e: Exception) { error = noNet }
                            busy = false
                        }
                    }, Modifier.fillMaxWidth(), enabled = email.isNotBlank() && pass.isNotBlank(), busy = busy)
                    Spacer(Modifier.height(8.dp))
                    SvButton(stringResource(R.string.get_archive), {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://svagagames.github.io/svaga-archive/"))) }
                    }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost, accent = Cyan)
                }
            }
            Spacer(Modifier.height(16.dp))
            SvCard(Modifier.fillMaxWidth(), onClick = {
                when {
                    EmuUpdate.file != null -> EmuUpdate.install(this@MainActivity)
                    EmuUpdate.version != null -> { EmuUpdate.dismissed = false; EmuUpdate.start(this@MainActivity, net) }
                    else -> EmuUpdate.check(this@MainActivity, manual = true)
                }
            }) {
                Body(stringResource(R.string.update_title) + " · v" + BuildConfig.VERSION_NAME, bold = true)
                Caption(
                    when {
                        EmuUpdate.checking -> stringResource(R.string.update_checking)
                        EmuUpdate.progress != null -> stringResource(R.string.update_downloading, ((EmuUpdate.progress ?: 0f) * 100).toInt())
                        EmuUpdate.version != null -> stringResource(R.string.update_available_v, EmuUpdate.version ?: "")
                        EmuUpdate.upToDate == true -> stringResource(R.string.update_latest)
                        EmuUpdate.failed -> stringResource(R.string.update_failed)
                        else -> stringResource(R.string.update_hint)
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            SvCard(Modifier.fillMaxWidth()) {
                Logo()
                Spacer(Modifier.height(10.dp))
                Caption(stringResource(R.string.legal), maxLines = 8)
                Spacer(Modifier.height(6.dp))
                Caption(stringResource(R.string.version), color = Faint)
            }
        }
    }

    @Composable
    private fun StatTile(value: String, label: String, modifier: Modifier, accent: Color = Ink) {
        SvCard(modifier, padding = 14.dp) {
            Text(value, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Caption(label, maxLines = 1)
        }
    }
}

// =================================================================== covers

private val THUMB_REPOS = mapOf(
    Console.NES to "Nintendo_-_Nintendo_Entertainment_System",
    Console.SNES to "Nintendo_-_Super_Nintendo_Entertainment_System",
    Console.MD to "Sega_-_Mega_Drive_-_Genesis",
    Console.SMS to "Sega_-_Master_System_-_Mark_III",
    Console.GG to "Sega_-_Game_Gear",
    Console.SEGACD to "Sega_-_Mega-CD_-_Sega_CD",
    Console.S32X to "Sega_-_32X",
    Console.GB to "Nintendo_-_Game_Boy",
    Console.GBC to "Nintendo_-_Game_Boy_Color",
    Console.GBA to "Nintendo_-_Game_Boy_Advance",
    Console.VB to "Nintendo_-_Virtual_Boy",
    Console.NDS to "Nintendo_-_Nintendo_DS",
    Console.N64 to "Nintendo_-_Nintendo_64",
    Console.PCE to "NEC_-_PC_Engine_-_TurboGrafx_16",
    Console.PS1 to "Sony_-_PlayStation",
    Console.PSP to "Sony_-_PlayStation_Portable",
    Console.DC to "Sega_-_Dreamcast",
    Console.SATURN to "Sega_-_Saturn",
    Console.A2600 to "Atari_-_2600",
    Console.A7800 to "Atari_-_7800",
    Console.LYNX to "Atari_-_Lynx",
    Console.NGP to "SNK_-_Neo_Geo_Pocket_Color",
    Console.WS to "Bandai_-_WonderSwan_Color",
    Console.ARCADE to "FBNeo_-_Arcade_Games",
)

/** Candidate box-art URLs: Svaga catalog cover, then libretro-thumbnails by the No-Intro file name. */
private fun coverCandidates(rom: Rom): List<String> {
    val out = mutableListOf<String>()
    rom.cover?.let { out += it }
    val repo = THUMB_REPOS[rom.system] ?: return out
    fun url(name: String): String {
        val safe = name.replace(Regex("[&*/:`<>?\\\\|\"]"), "_")
        return "https://raw.githubusercontent.com/libretro-thumbnails/$repo/master/Named_Boxarts/" +
            URLEncoder.encode(safe, "UTF-8").replace("+", "%20") + ".png"
    }
    rom.original?.let { out += url(it) }
    listOf("USA", "Europe", "World", "Japan", "USA, Europe").forEach { out += url("${rom.title} ($it)") }
    return out.distinct()
}

fun systemColors(c: Console): List<Color> = when (c) {
    Console.NES, Console.SNES, Console.N64, Console.GB, Console.GBC, Console.GBA, Console.NDS, Console.VB, Console.MINI -> listOf(Color(0xFFFF5A6A), Color(0xFFB98BFF))
    Console.MD, Console.SMS, Console.GG, Console.SEGACD, Console.S32X, Console.DC, Console.SATURN -> listOf(Color(0xFF3EE0FF), Color(0xFF4F7CFF))
    Console.PS1, Console.PSP -> listOf(Color(0xFF4F7CFF), Color(0xFF6E45C8))
    Console.ARCADE, Console.DOOM -> listOf(Color(0xFFFFB547), Color(0xFFFF5A6A))
    else -> listOf(Color(0xFF3DDC84), Color(0xFF0596C2))
}

@Composable
fun RomCover(rom: Rom, modifier: Modifier, shape: RoundedCornerShape, showLabel: Boolean = true) {
    val urls = remember(rom.id, rom.cover, rom.original, rom.title, rom.system) { coverCandidates(rom) }
    var idx by remember(urls) { mutableIntStateOf(0) }
    Box(modifier.clip(shape).background(Brush.linearGradient(systemColors(rom.system).map { it.copy(alpha = 0.55f) }))) {
        if (showLabel) {
            Column(Modifier.align(Alignment.Center).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(rom.system.label, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(rom.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 4)
            }
        }
        if (idx < urls.size) {
            AsyncImage(
                model = urls[idx], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onError = { idx++ },
            )
        }
    }
}
