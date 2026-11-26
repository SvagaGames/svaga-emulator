package com.svaga.emulator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Same palette as Svaga Archive.
val Bg = Color(0xFF0C1424)
val Panel = Color(0xFF131E34)
val Panel2 = Color(0xFF1A2742)
val Ink = Color(0xFFE8EEFF)
val Muted = Color(0xFF8A97B8)
val Line = Color(0xFF2A3A5E)
val Cyan = Color(0xFF3EE0FF)
val Red = Color(0xFFFF5470)
val Green = Color(0xFF5CE08A)
val Amber = Color(0xFFFFC857)

@Composable
fun SvagaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Bg, surface = Panel, onSurface = Ink, onBackground = Ink)) {
        Box(Modifier.background(Bg)) { content() }
    }
}

@Composable
fun PixelButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = Cyan, filled: Boolean = true, enabled: Boolean = true) {
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (filled) accent else Color.Transparent, RectangleShape)
            .border(2.dp, accent, RectangleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(PaddingValues(horizontal = 14.dp, vertical = 10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Bg else accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun PixelChip(text: String, accent: Color = Cyan, modifier: Modifier = Modifier) {
    Box(modifier.border(1.dp, accent, RectangleShape).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

val Mono = TextStyle(fontFamily = FontFamily.Monospace, color = Ink)
