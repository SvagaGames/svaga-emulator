package com.svaga.emulator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var store: RomStore
    private lateinit var session: Session
    private lateinit var net: Net

    // A game requested by Svaga Archive that has no ROM yet: the next import is linked to it.
    private var pendingGameId by mutableStateOf<Int?>(null)
    private var pendingGameName by mutableStateOf<String?>(null)
    private var roms by mutableStateOf<List<Rom>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RomStore(this)
        session = Session(this)
        net = Net(session)
        roms = store.all()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { SvagaTheme { LibraryScreen() } }
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

    private fun fmtDur(seconds: Long): String {
        val m = (seconds / 60).toInt()
        return if (m < 60) getString(R.string.playtime_min, m) else getString(R.string.playtime_h, m / 60, m % 60)
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
            Toast.makeText(this, getString(R.string.launch_missing, name), Toast.LENGTH_LONG).show()
        }
    }

    private fun play(rom: Rom) {
        if (!rom.system.coreInstalled(this)) {
            Toast.makeText(this, R.string.core_missing, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("romId", rom.id))
    }

    @Composable
    private fun LibraryScreen() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var selected by remember { mutableStateOf<Rom?>(null) }
        var showAccount by remember { mutableStateOf(false) }
        var showPlayers by remember { mutableStateOf(false) }
        var signedIn by remember { mutableStateOf(session.signedIn) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.import(uris) }
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

        val biosPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.importBios(uris) }
                Toast.makeText(ctx, ctx.getString(R.string.bios_added_n, n), Toast.LENGTH_LONG).show()
            }
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SVAGA EMULATOR", style = Mono, fontSize = 20.sp, color = Cyan)
                    Text(stringResource(R.string.subtitle), style = Mono, fontSize = 10.sp, color = Muted)
                }
                if (signedIn) {
                    PixelButton(stringResource(R.string.players), { showPlayers = true }, accent = Amber, filled = false)
                    Spacer(Modifier.width(6.dp))
                }
                PixelButton(stringResource(R.string.account), { showAccount = true }, accent = if (signedIn) Green else Muted, filled = false)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(stringResource(R.string.import_roms), { picker.launch(arrayOf("*/*")) }, Modifier.weight(1f))
                PixelButton(stringResource(R.string.import_bios), { biosPicker.launch(arrayOf("*/*")) }, accent = Amber, filled = false)
            }
            pendingGameName?.let {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.launch_missing, it), style = Mono, fontSize = 12.sp, color = Amber)
            }
            Spacer(Modifier.height(12.dp))
            if (roms.isEmpty()) {
                Text(stringResource(R.string.empty_library), style = Mono, fontSize = 13.sp, color = Muted)
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(roms.sortedByDescending { it.lastPlayed }, key = { it.id }) { rom ->
                        RomRow(rom, onPlay = { play(rom) }, onMore = { selected = rom })
                    }
                }
            }
            Spacer(Modifier.weight(if (roms.isEmpty()) 1f else 0.0001f))
            Text(stringResource(R.string.legal), style = Mono, fontSize = 9.sp, color = Muted)
            Text(stringResource(R.string.version), style = Mono, fontSize = 9.sp, color = Muted)
        }

        selected?.let { rom ->
            RomDialog(rom, signedIn, onClose = { selected = null }, onChanged = { roms = store.all(); selected = store.get(rom.id) },
                onPlay = { selected = null; play(rom) },
                onDelete = { store.delete(rom); roms = store.all(); selected = null },
                onNeedLogin = { selected = null; showAccount = true })
        }
        if (showPlayers) PlayersDialog(onClose = { showPlayers = false })
        if (showAccount) AccountDialog(onClose = { showAccount = false; signedIn = session.signedIn })
    }

    @Composable
    private fun RomRow(rom: Rom, onPlay: () -> Unit, onMore: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().background(Panel).border(1.dp, Line, RectangleShape)
                .clickable(onClick = onPlay).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(rom.title, style = Mono, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PixelChip(rom.system.label)
                    if (rom.gameId != null) PixelChip("SVAGA", Green)
                    Text(playtimeText(rom), style = Mono, fontSize = 10.sp, color = Muted)
                }
            }
            PixelButton("…", onMore, accent = Muted, filled = false)
        }
    }

    @Composable
    private fun playtimeText(rom: Rom): String {
        if (rom.playSeconds < 30) return stringResource(R.string.never_played)
        val m = (rom.playSeconds / 60).toInt()
        return if (m < 60) stringResource(R.string.playtime_min, m) else stringResource(R.string.playtime_h, m / 60, m % 60)
    }

    @Composable
    private fun RomDialog(rom: Rom, signedIn: Boolean, onClose: () -> Unit, onChanged: () -> Unit, onPlay: () -> Unit, onDelete: () -> Unit, onNeedLogin: () -> Unit) {
        var confirmDelete by remember { mutableStateOf(false) }
        var linking by remember { mutableStateOf(false) }
        var choosing by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf(rom.title) }
        var results by remember { mutableStateOf<List<FoundGame>?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val ctx = LocalContext.current
        var community by remember { mutableStateOf<Community?>(null) }
        var myRating by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(rom.gameId, signedIn) {
            val gid = rom.gameId
            community = if (gid != null && signedIn) runCatching { net.community(gid) }.getOrNull() else null
        }

        AlertDialog(
            onDismissRequest = onClose,
            containerColor = Panel,
            title = { Text(rom.title, style = Mono, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PixelChip(rom.system.label)
                        if (rom.gameName != null) PixelChip(stringResource(R.string.linked_with, rom.gameName), Green)
                    }
                    PixelButton(stringResource(R.string.play), onPlay, Modifier.fillMaxWidth())
                    community?.let { c ->
                        Column(Modifier.fillMaxWidth().background(Panel2).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.community), style = Mono, fontSize = 11.sp, color = Amber)
                            Text(
                                (c.avgRating?.let { "★ $it (${c.ratingCount})  ·  " } ?: "") +
                                    stringResource(R.string.community_total, fmtDur(c.totalSeconds), c.players),
                                style = Mono, fontSize = 12.sp,
                            )
                            c.top.take(5).forEachIndexed { i, p ->
                                Text("${i + 1}. ${p.name} — ${fmtDur(p.seconds)}" + (p.rating?.let { "  ★$it" } ?: ""), style = Mono, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(stringResource(R.string.rate_game), style = Mono, fontSize = 11.sp, color = Amber)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                (1..10).forEach { n ->
                                    Box(
                                        Modifier.size(30.dp).background(if (myRating == n) Amber else Panel).border(1.dp, Amber, RectangleShape)
                                            .clickable {
                                                myRating = n
                                                scope.launch {
                                                    runCatching { net.rate(rom.gameId!!, n); community = net.community(rom.gameId!!) }
                                                }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) { Text("$n", style = Mono, fontSize = 12.sp, color = if (myRating == n) Bg else Amber) }
                                }
                            }
                        }
                    }
                    if (choosing) {
                        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Console.chooser.forEach { c ->
                                val ok = c.coreInstalled(ctx)
                                Box(
                                    Modifier.fillMaxWidth().background(if (c == rom.system) Cyan else Panel2).clickable(enabled = ok) {
                                        store.update(rom.id) { it.copy(system = c) }; choosing = false; onChanged()
                                    }.padding(8.dp),
                                ) {
                                    Text(c.label + if (ok) "" else "  (" + stringResource(R.string.core_not_in_build) + ")", style = Mono, fontSize = 12.sp,
                                        color = if (c == rom.system) Bg else if (ok) Ink else Muted)
                                }
                            }
                        }
                    } else if (!linking) {
                        PixelButton(stringResource(R.string.change_system, rom.system.label), { choosing = true }, Modifier.fillMaxWidth(), accent = Amber, filled = false)
                        PixelButton(
                            stringResource(R.string.link_to_svaga),
                            { if (signedIn) linking = true else onNeedLogin() },
                            Modifier.fillMaxWidth(), accent = Green, filled = false,
                        )
                        if (rom.gameId != null) PixelButton(stringResource(R.string.unlink), {
                            store.update(rom.id) { it.copy(gameId = null, gameName = null) }; onChanged()
                        }, Modifier.fillMaxWidth(), accent = Muted, filled = false)
                        PixelButton(stringResource(R.string.delete), { confirmDelete = true }, Modifier.fillMaxWidth(), accent = Red, filled = false)
                    } else {
                        OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.search_hint)) }, modifier = Modifier.fillMaxWidth())
                        PixelButton(stringResource(R.string.search), {
                            busy = true; error = null
                            scope.launch {
                                try { results = net.search(query.trim()) }
                                catch (e: ApiError) { error = e.code }
                                catch (e: Exception) { error = "net" }
                                busy = false
                            }
                        }, Modifier.fillMaxWidth(), enabled = !busy && query.isNotBlank())
                        if (error != null) Text(if (error == "net") stringResource(R.string.no_connection) else error!!, style = Mono, fontSize = 11.sp, color = Red)
                        results?.let { list ->
                            if (list.isEmpty()) Text(stringResource(R.string.not_found), style = Mono, fontSize = 12.sp, color = Muted)
                            list.take(8).forEach { g ->
                                Column(Modifier.fillMaxWidth().background(Panel2).clickable {
                                    store.update(rom.id) { it.copy(gameId = g.id, gameName = g.name) }
                                    linking = false; results = null; onChanged()
                                }.padding(8.dp)) {
                                    Text(g.name + (g.year?.let { " ($it)" } ?: ""), style = Mono, fontSize = 13.sp)
                                    if (g.platforms.isNotEmpty()) Text(g.platforms, style = Mono, fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClose) { Text(stringResource(R.string.close), color = Cyan) } },
        )
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Panel,
            text = { Text(stringResource(R.string.delete_confirm, rom.title), style = Mono) },
            confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text(stringResource(R.string.delete), color = Red) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel), color = Muted) } },
        )
    }

    @Composable
    private fun PlayersDialog(onClose: () -> Unit) {
        var query by remember { mutableStateOf("") }
        var people by remember { mutableStateOf<List<Person>?>(null) }
        var error by remember { mutableStateOf(false) }
        LaunchedEffect(query) {
            kotlinx.coroutines.delay(300)
            error = false
            try { people = net.users(query.trim()) } catch (e: Exception) { error = true }
        }
        AlertDialog(
            onDismissRequest = onClose,
            containerColor = Panel,
            title = { Text(stringResource(R.string.players), style = Mono) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.search_hint)) }, modifier = Modifier.fillMaxWidth())
                    if (error) Text(stringResource(R.string.no_connection), style = Mono, fontSize = 12.sp, color = Red)
                    people?.let { list ->
                        if (list.isEmpty() && !error) Text(stringResource(R.string.not_found), style = Mono, fontSize = 12.sp, color = Muted)
                        LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(list, key = { it.id }) { p ->
                                Column(Modifier.fillMaxWidth().background(Panel2).padding(8.dp)) {
                                    Text(p.name, style = Mono, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(R.string.person_line, p.level, p.library, p.completed), style = Mono, fontSize = 10.sp, color = Muted)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClose) { Text(stringResource(R.string.close), color = Cyan) } },
        )
    }

    @Composable
    private fun AccountDialog(onClose: () -> Unit) {
        var email by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var signed by remember { mutableStateOf(session.signedIn) }
        val scope = rememberCoroutineScope()
        val failed = stringResource(R.string.login_failed)
        val noNet = stringResource(R.string.no_connection)
        AlertDialog(
            onDismissRequest = onClose,
            containerColor = Panel,
            title = { Text(stringResource(R.string.account), style = Mono) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (signed) {
                        Text(stringResource(R.string.signed_as, session.email ?: ""), style = Mono, fontSize = 13.sp)
                        PixelButton(stringResource(R.string.sign_out), { session.clear(); signed = false }, Modifier.fillMaxWidth(), accent = Red, filled = false)
                    } else {
                        OutlinedTextField(email, { email = it }, singleLine = true, label = { Text(stringResource(R.string.email)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text(stringResource(R.string.password)) },
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        error?.let { Text(it, style = Mono, fontSize = 11.sp, color = Red) }
                        PixelButton(stringResource(R.string.sign_in), {
                            busy = true; error = null
                            scope.launch {
                                try { net.login(email.trim(), pass); signed = true }
                                catch (e: ApiError) { error = failed }
                                catch (e: Exception) { error = noNet }
                                busy = false
                            }
                        }, Modifier.fillMaxWidth(), enabled = !busy && email.isNotBlank() && pass.isNotBlank())
                    }
                }
            },
            confirmButton = { TextButton(onClose) { Text(stringResource(R.string.close), color = Cyan) } },
        )
    }
}
