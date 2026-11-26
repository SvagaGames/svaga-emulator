package com.svaga.emulator

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Key codes are Android gamepad codes, which LibretroDroid maps by physical position:
 * BUTTON_A = bottom (retro B), BUTTON_B = right (retro A), BUTTON_X = left (retro Y), BUTTON_Y = top (retro X).
 */
typealias KeySender = (keyCode: Int, down: Boolean) -> Unit
/** Sends a stick position (-1..1) to the left or right analog stick of the core. */
typealias AnalogSender = (right: Boolean, x: Float, y: Float) -> Unit

@Composable
fun HoldButton(label: String, keys: List<Int>, send: KeySender, size: Dp = 56.dp, round: Boolean = true) {
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val shape = if (round) CircleShape else RectangleShape
    Box(
        Modifier.size(size)
            .background(if (pressed) Cyan.copy(alpha = 0.55f) else Color(0x66131E34), shape)
            .border(2.dp, Cyan.copy(alpha = 0.8f), shape)
            .pointerInput(keys) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    keys.forEach { send(it, true) }
                    waitForUpOrCancellation()
                    keys.forEach { send(it, false) }
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Mono, fontSize = 15.sp, color = Color.White) }
}

@Composable
fun DPad(send: KeySender, size: Dp = 150.dp) {
    var held by remember { mutableStateOf(emptySet<Int>()) }
    Box(
        Modifier.size(size)
            .background(Color(0x44131E34), CircleShape)
            .border(2.dp, Cyan.copy(alpha = 0.6f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var cur = emptySet<Int>()
                    fun apply(pos: Offset) {
                        val dx = pos.x - this.size.width / 2f
                        val dy = pos.y - this.size.height / 2f
                        val dz = this.size.width * 0.14f
                        val next = buildSet {
                            if (dx < -dz) add(KeyEvent.KEYCODE_DPAD_LEFT) else if (dx > dz) add(KeyEvent.KEYCODE_DPAD_RIGHT)
                            if (dy < -dz) add(KeyEvent.KEYCODE_DPAD_UP) else if (dy > dz) add(KeyEvent.KEYCODE_DPAD_DOWN)
                        }
                        (cur - next).forEach { send(it, false) }
                        (next - cur).forEach { send(it, true) }
                        cur = next
                        held = next
                    }
                    down.consume()
                    apply(down.position)
                    do {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull()
                        if (ch != null) {
                            if (ch.pressed) apply(ch.position)
                            ch.consume()
                        }
                    } while (ev.changes.any { it.pressed })
                    cur.forEach { send(it, false) }
                    held = emptySet()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                KeyEvent.KEYCODE_DPAD_UP in held -> "▲"
                KeyEvent.KEYCODE_DPAD_DOWN in held -> "▼"
                KeyEvent.KEYCODE_DPAD_LEFT in held -> "◀"
                KeyEvent.KEYCODE_DPAD_RIGHT in held -> "▶"
                else -> "✚"
            },
            style = Mono, fontSize = 28.sp, color = Cyan.copy(alpha = 0.8f),
        )
    }
}

@Composable
fun AnalogStick(analog: AnalogSender, right: Boolean = false, size: Dp = 150.dp) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.size(size)
            .background(Color(0x44131E34), CircleShape)
            .border(2.dp, Cyan.copy(alpha = 0.6f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun apply(pos: Offset) {
                        val r = this.size.width / 2f
                        var x = (pos.x - r) / r
                        var y = (pos.y - r) / r
                        val len = kotlin.math.sqrt(x * x + y * y)
                        if (len > 1f) { x /= len; y /= len }
                        knob = Offset(x, y)
                        analog(right, x, y)
                    }
                    down.consume()
                    apply(down.position)
                    do {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull()
                        if (ch != null) { if (ch.pressed) apply(ch.position); ch.consume() }
                    } while (ev.changes.any { it.pressed })
                    knob = Offset.Zero
                    analog(right, 0f, 0f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.offset(x = (knob.x * size.value * 0.3f).dp, y = (knob.y * size.value * 0.3f).dp)
                .size(size * 0.38f).background(Cyan.copy(alpha = 0.55f), CircleShape),
        )
    }
}

private const val A_BTN = KeyEvent.KEYCODE_BUTTON_A   // bottom
private const val B_BTN = KeyEvent.KEYCODE_BUTTON_B   // right
private const val X_BTN = KeyEvent.KEYCODE_BUTTON_X   // left
private const val Y_BTN = KeyEvent.KEYCODE_BUTTON_Y   // top

/** Face buttons laid out for the system's real controller. */
@Composable
fun FaceButtons(console: Console, send: KeySender) {
    when (console) {
        Console.PS1, Console.PSP -> Box(Modifier.size(170.dp)) {
            Box(Modifier.align(Alignment.TopCenter)) { HoldButton("△", listOf(Y_BTN), send) }
            Box(Modifier.align(Alignment.BottomCenter)) { HoldButton("✕", listOf(A_BTN), send) }
            Box(Modifier.align(Alignment.CenterStart)) { HoldButton("□", listOf(X_BTN), send) }
            Box(Modifier.align(Alignment.CenterEnd)) { HoldButton("○", listOf(B_BTN), send) }
        }
        Console.NDS, Console.DC, Console.SATURN -> Box(Modifier.size(170.dp)) {
            Box(Modifier.align(Alignment.TopCenter)) { HoldButton("X", listOf(Y_BTN), send) }
            Box(Modifier.align(Alignment.BottomCenter)) { HoldButton("B", listOf(A_BTN), send) }
            Box(Modifier.align(Alignment.CenterStart)) { HoldButton("Y", listOf(X_BTN), send) }
            Box(Modifier.align(Alignment.CenterEnd)) { HoldButton("A", listOf(B_BTN), send) }
        }
        Console.N64 -> Box(Modifier.size(170.dp)) {
            // N64 A/B plus Z; the C buttons are on the right stick (see TouchControls)
            Box(Modifier.align(Alignment.BottomStart)) { HoldButton("B", listOf(X_BTN), send) }
            Box(Modifier.align(Alignment.BottomEnd)) { HoldButton("A", listOf(A_BTN), send) }
            Box(Modifier.align(Alignment.TopCenter)) { HoldButton("Z", listOf(KeyEvent.KEYCODE_BUTTON_L2), send, 48.dp) }
        }
        Console.SNES -> Box(Modifier.size(170.dp)) {
            Box(Modifier.align(Alignment.TopCenter)) { HoldButton("X", listOf(Y_BTN), send) }
            Box(Modifier.align(Alignment.BottomCenter)) { HoldButton("B", listOf(A_BTN), send) }
            Box(Modifier.align(Alignment.CenterStart)) { HoldButton("Y", listOf(X_BTN), send) }
            Box(Modifier.align(Alignment.CenterEnd)) { HoldButton("A", listOf(B_BTN), send) }
        }
        Console.MD -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            HoldButton("A", listOf(A_BTN), send)
            Box(Modifier.padding(bottom = 22.dp)) { HoldButton("B", listOf(B_BTN), send) }
            Box(Modifier.padding(bottom = 44.dp)) { HoldButton("C", listOf(KeyEvent.KEYCODE_BUTTON_R1), send) }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            HoldButton("B", listOf(A_BTN), send, 62.dp)
            Box(Modifier.padding(bottom = 34.dp)) { HoldButton("A", listOf(B_BTN), send, 62.dp) }
        }
    }
}

@Composable
fun ShoulderRow(console: Console, send: KeySender, modifier: Modifier = Modifier) {
    if (console !in setOf(Console.SNES, Console.GBA, Console.PS1, Console.NDS, Console.N64, Console.PSP, Console.DC, Console.SATURN, Console.A5200)) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HoldButton(if (console == Console.PS1) "L1" else "L", listOf(KeyEvent.KEYCODE_BUTTON_L1), send, 52.dp, round = false)
            if (console == Console.PS1) HoldButton("L2", listOf(KeyEvent.KEYCODE_BUTTON_L2), send, 52.dp, round = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (console == Console.PS1) HoldButton("R2", listOf(KeyEvent.KEYCODE_BUTTON_R2), send, 52.dp, round = false)
            HoldButton(if (console == Console.PS1) "R1" else "R", listOf(KeyEvent.KEYCODE_BUTTON_R1), send, 52.dp, round = false)
        }
    }
}

@Composable
fun StartSelect(send: KeySender, showSelect: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showSelect) HoldButton("SEL", listOf(KeyEvent.KEYCODE_BUTTON_SELECT), send, 46.dp, round = false)
        HoldButton("STA", listOf(KeyEvent.KEYCODE_BUTTON_START), send, 46.dp, round = false)
    }
}

/** Full touch overlay: shoulders on top, d-pad (or analog stick) left, face buttons right, start/select in the middle bottom. */
@Composable
fun TouchControls(console: Console, send: KeySender, analog: AnalogSender, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(12.dp)) {
        ShoulderRow(console, send, Modifier.align(Alignment.TopCenter))
        Box(Modifier.align(Alignment.BottomStart)) { if (console.analog) AnalogStick(analog) else DPad(send) }
        Box(Modifier.align(Alignment.BottomEnd)) { FaceButtons(console, send) }
        if (console.analog) {
            // C buttons as the right stick pushed in four directions
            Box(Modifier.align(Alignment.CenterEnd).padding(bottom = 120.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CButton("C▲", analog, 0f, -1f)
                    Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) { CButton("C◀", analog, -1f, 0f); CButton("C▶", analog, 1f, 0f) }
                    CButton("C▼", analog, 0f, 1f)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter)) { StartSelect(send, showSelect = console != Console.N64) }
    }
}

@Composable
private fun CButton(label: String, analog: AnalogSender, x: Float, y: Float) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier.size(40.dp)
            .background(if (pressed) Amber.copy(alpha = 0.6f) else Color(0x66131E34), CircleShape)
            .border(2.dp, Amber.copy(alpha = 0.8f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    analog(true, x, y)
                    waitForUpOrCancellation()
                    analog(true, 0f, 0f)
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Mono, fontSize = 11.sp, color = Color.White) }
}
