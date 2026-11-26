package com.svaga.emulator

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import java.io.File

class PlayerActivity : ComponentActivity() {
    private lateinit var store: RomStore
    private lateinit var rom: Rom
    private var retro: GLRetroView? = null
    private var startedAt = 0L
    private var pendingSeconds = 0L

    private val padKeys = setOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    private val srmFile get() = File(store.savesDir, rom.id + ".srm")
    private fun stateFile(slot: Int) = File(store.savesDir, rom.id + ".state$slot")

    private var menuOpen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RomStore(this)
        val r = intent.getStringExtra("romId")?.let { store.get(it) }
        if (r == null || !r.system.coreInstalled(this)) {
            Toast.makeText(this, R.string.core_missing, Toast.LENGTH_LONG).show()
            finish(); return
        }
        rom = r
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val data = GLRetroViewData(this).apply {
            coreFilePath = rom.system.corePath(this@PlayerActivity)!!
            gameFilePath = store.path(rom)
            systemDirectory = store.systemDir.path
            savesDirectory = store.savesDir.path
            shader = shaderFor(prefs().getInt("shader", 0))
            if (srmFile.exists()) saveRAMState = runCatching { srmFile.readBytes() }.getOrNull()
        }
        val view = GLRetroView(this, data)
        lifecycle.addObserver(view)
        retro = view
        if (rom.system.touchScreen) {
            // The picture is the touch surface: pass the finger position (0..1 inside the view) as the core's pointer.
            // LibretroDroid's pointer API is used from memory and is unverified.
            view.setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                    runCatching { view.sendMotionEvent(GLRetroView.MOTION_SOURCE_POINTER, -1f, -1f) }
                } else {
                    runCatching { view.sendMotionEvent(GLRetroView.MOTION_SOURCE_POINTER, e.x / v.width, e.y / v.height) }
                }
                true
            }
        }

        setContent { SvagaTheme { PlayerScreen(view) } }
    }

    private fun prefs() = getSharedPreferences("svaga_player", Context.MODE_PRIVATE)

    private fun shaderFor(i: Int): ShaderConfig = when (i) {
        1 -> ShaderConfig.CRT
        2 -> ShaderConfig.LCD
        3 -> ShaderConfig.Sharp
        else -> ShaderConfig.Default
    }

    private fun gamepadConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { d -> (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD } == true
    }

    private fun send(keyCode: Int, down: Boolean) {
        retro?.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode)
    }

    private fun flushSram() {
        runCatching {
            val bytes = retro?.serializeSRAM()
            if (bytes != null && bytes.isNotEmpty()) srmFile.writeBytes(bytes)
        }
    }

    override fun onResume() {
        super.onResume()
        startedAt = SystemClock.elapsedRealtime()
    }

    override fun onPause() {
        super.onPause()
        pendingSeconds += (SystemClock.elapsedRealtime() - startedAt) / 1000
        startedAt = SystemClock.elapsedRealtime()
        if (pendingSeconds > 0) {
            store.addPlaytime(rom.id, pendingSeconds)
            store.get(rom.id)?.gameId?.let { PlaytimeQueue(this).add(it, pendingSeconds) }
            pendingSeconds = 0
        }
        flushSram()
    }

    // ---- hardware gamepad / keyboard ----
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && event.action == KeyEvent.ACTION_DOWN) { menuOpen = !menuOpen; return true }
        val fromPad = (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD or InputDevice.SOURCE_KEYBOARD)) != 0
        if (!menuOpen && fromPad && event.keyCode in padKeys && event.repeatCount == 0) {
            retro?.sendKeyEvent(event.action, event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!menuOpen && (ev.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && ev.action == MotionEvent.ACTION_MOVE) {
            val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val x = if (hx != 0f) hx else ev.getAxisValue(MotionEvent.AXIS_X)
            val y = if (hy != 0f) hy else ev.getAxisValue(MotionEvent.AXIS_Y)
            if (rom.system.hwAnalog) {
                retro?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y))
                retro?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, ev.getAxisValue(MotionEvent.AXIS_Z), ev.getAxisValue(MotionEvent.AXIS_RZ))
                if (hx != 0f || hy != 0f) retro?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, hx, hy)
            } else {
                retro?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    // ---- UI ----
    @Composable
    private fun PlayerScreen(view: GLRetroView) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        var touch by remember { mutableStateOf(prefs().getBoolean("touch", !gamepadConnected())) }
        val sender: KeySender = { k, d -> send(k, d) }
        val analog: AnalogSender = { right, x, y ->
            runCatching { view.sendMotionEvent(if (right) GLRetroView.MOTION_SOURCE_ANALOG_RIGHT else GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y) }
        }

        BackHandler { menuOpen = true }
        LaunchedEffect(menuOpen) {
            // Pause emulation while the menu is open. frameSpeed/audioEnabled are LibretroDroid properties (API unverified).
            runCatching { view.frameSpeed = if (menuOpen) 0 else 1; view.audioEnabled = !menuOpen }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (landscape) {
                AndroidView({ view }, Modifier.fillMaxSize())
                if (touch) TouchControls(rom.system, sender, analog)
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    AndroidView({ view }, Modifier.fillMaxWidth().weight(1f))
                    if (touch) Box(Modifier.fillMaxWidth().height(300.dp)) { TouchControls(rom.system, sender, analog) }
                }
            }
            // menu button
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                PixelButton("≡", { menuOpen = true }, accent = Muted, filled = false)
            }
            if (menuOpen) MenuPanel(
                touch = touch,
                onTouch = { touch = it; prefs().edit().putBoolean("touch", it).apply() },
                view = view,
            )
        }
    }

    @Composable
    private fun MenuPanel(touch: Boolean, onTouch: (Boolean) -> Unit, view: GLRetroView) {
        var slot by remember { mutableIntStateOf(1) }
        var shader by remember { mutableIntStateOf(prefs().getInt("shader", 0)) }
        val ctx = this@PlayerActivity
        val savedMsg = stringResource(R.string.saved)
        val loadedMsg = stringResource(R.string.loaded)
        val emptyMsg = stringResource(R.string.no_state)
        val failedMsg = stringResource(R.string.load_failed)
        val shaderNames = listOf(R.string.shader_off, R.string.shader_crt, R.string.shader_lcd, R.string.shader_sharp).map { stringResource(it) }

        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 360.dp).background(Panel).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(rom.title, style = Mono, fontSize = 15.sp, color = Cyan, maxLines = 1)
                PixelButton(stringResource(R.string.resume), { menuOpen = false }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..3).forEach { s ->
                        PixelButton(stringResource(R.string.slot, s), { slot = s }, Modifier.weight(1f), filled = slot == s, accent = if (slot == s) Cyan else Muted)
                    }
                }
                PixelButton(stringResource(R.string.save_state), {
                    runCatching { stateFile(slot).writeBytes(view.serializeState()) }
                        .onSuccess { Toast.makeText(ctx, savedMsg, Toast.LENGTH_SHORT).show(); menuOpen = false }
                        .onFailure { Toast.makeText(ctx, failedMsg, Toast.LENGTH_SHORT).show() }
                }, Modifier.fillMaxWidth(), accent = Green, filled = false)
                PixelButton(stringResource(R.string.load_state), {
                    val f = stateFile(slot)
                    if (!f.exists()) Toast.makeText(ctx, emptyMsg, Toast.LENGTH_SHORT).show()
                    else runCatching { view.unserializeState(f.readBytes()) }
                        .onSuccess { Toast.makeText(ctx, loadedMsg, Toast.LENGTH_SHORT).show(); menuOpen = false }
                        .onFailure { Toast.makeText(ctx, failedMsg, Toast.LENGTH_SHORT).show() }
                }, Modifier.fillMaxWidth(), accent = Green, filled = false)
                PixelButton(stringResource(R.string.shader, shaderNames[shader]), {
                    shader = (shader + 1) % 4
                    prefs().edit().putInt("shader", shader).apply()
                    runCatching { view.shader = shaderFor(shader) }
                }, Modifier.fillMaxWidth(), accent = Amber, filled = false)
                PixelButton(
                    stringResource(R.string.touch_controls, stringResource(if (touch) R.string.on else R.string.off)),
                    { onTouch(!touch) }, Modifier.fillMaxWidth(), accent = Amber, filled = false,
                )
                PixelButton(stringResource(R.string.reset), { runCatching { view.reset() }; menuOpen = false }, Modifier.fillMaxWidth(), accent = Red, filled = false)
                PixelButton(stringResource(R.string.exit_game), { flushSram(); finish() }, Modifier.fillMaxWidth(), accent = Red)
            }
        }
    }
}
