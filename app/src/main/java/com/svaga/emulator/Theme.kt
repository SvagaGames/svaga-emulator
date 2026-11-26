package com.svaga.emulator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The Svaga Archive design system (dark): same colours, rounded cards, gradient accent.
val Bg = Color(0xFF0A0C11)
val Panel = Color(0xFF14171F)
val Panel2 = Color(0xFF1C2029)
val Line = Color(0xFF262B36)
val Ink = Color(0xFFF3F5F9)
val Muted = Color(0xFF9AA3B5)
val Faint = Color(0xFF5B6375)
val Cyan = Color(0xFF3EE0FF)
val Blue = Color(0xFF4F7CFF)
val Red = Color(0xFFFF5A6A)
val Green = Color(0xFF3DDC84)
val Amber = Color(0xFFFFB547)
val Purple = Color(0xFFB98BFF)
val AccentBrush = Brush.horizontalGradient(listOf(Cyan, Blue))
val CardShape = RoundedCornerShape(20.dp)
val CoverShape = RoundedCornerShape(14.dp)

@Composable
fun SvagaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Bg, surface = Panel, onSurface = Ink, onBackground = Ink)) {
        Box(Modifier.background(Bg)) { content() }
    }
}

/** Text style kept for the in-game overlay (gamepad labels). */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, color = Ink)

@Composable
fun H1(text: String, modifier: Modifier = Modifier, color: Color = Ink) =
    Text(text, color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp, modifier = modifier)

@Composable
fun H2(text: String, modifier: Modifier = Modifier, color: Color = Ink) =
    Text(text, color = color, fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp, modifier = modifier)

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color = Ink, size: Int = 15, maxLines: Int = Int.MAX_VALUE, bold: Boolean = false) =
    Text(
        text, color = color, fontSize = size.sp, lineHeight = (size * 1.38f).sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = modifier,
    )

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Muted, maxLines: Int = 2) = Body(text, modifier, color, 13, maxLines)

@Composable
fun Logo(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("SVAGA", color = Cyan, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.width(8.dp))
        Text("EMULATOR", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
    }
}

enum class BtnKind { Primary, Secondary, Ghost, Danger }

@Composable
fun SvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Primary,
    enabled: Boolean = true,
    busy: Boolean = false,
    accent: Color? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val base = modifier.alpha(if (enabled) 1f else 0.45f).heightIn(min = 50.dp).clip(shape)
    val styled = when (kind) {
        BtnKind.Primary -> if (accent != null) base.background(accent, shape) else base.background(AccentBrush, shape)
        BtnKind.Secondary -> base.background(Panel2, shape).border(1.dp, Line, shape)
        BtnKind.Ghost -> base.border(1.dp, accent ?: Line, shape)
        BtnKind.Danger -> base.background(Red.copy(alpha = 0.14f), shape).border(1.dp, Red.copy(alpha = 0.5f), shape)
    }
    val fg = when (kind) {
        BtnKind.Primary -> Color(0xFF071018)
        BtnKind.Secondary -> Ink
        BtnKind.Ghost -> accent ?: Ink
        BtnKind.Danger -> Red
    }
    Box(styled.clickable(enabled = enabled && !busy, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** Compatibility with the in-game menu: same look as SvButton, smaller. */
@Composable
fun PixelButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = Cyan, filled: Boolean = true, enabled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .then(if (filled) Modifier.background(if (accent == Cyan) AccentBrush else SolidColor(accent), shape) else Modifier.background(Panel2, shape))
            .border(1.dp, if (filled) Color.Transparent else accent.copy(alpha = 0.6f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(PaddingValues(horizontal = 14.dp, vertical = 11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Color(0xFF071018) else accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun PixelChip(text: String, accent: Color = Cyan, modifier: Modifier = Modifier) = Pill(text, accent, modifier)

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Text(
        text, color = if (filled) Color(0xFF071018) else color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.clip(shape).background(if (filled) color else color.copy(alpha = 0.14f), shape).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun SvChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: Int? = null) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(if (selected) Cyan.copy(alpha = 0.18f) else Panel, shape)
            .border(1.dp, if (selected) Cyan else Line, shape).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (selected) Cyan else Ink, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), color = if (selected) Cyan else Muted, fontSize = 13.sp)
        }
    }
}

@Composable
fun SvCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 16.dp, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(CardShape).background(Panel, CardShape).border(1.dp, border ?: Line, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(padding),
        content = content,
    )
}

@Composable
fun SvField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    Column(modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        val shape = RoundedCornerShape(16.dp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 16.sp), cursorBrush = SolidColor(Cyan),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
            modifier = Modifier.fillMaxWidth().background(Panel, shape).border(1.dp, Line, shape).padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, count: Int? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        H2(title)
        if (count != null) {
            Spacer(Modifier.width(10.dp))
            Text(count.toString(), color = Faint, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun ProgressLine(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Panel2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}
