package org.pianocoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visual tokens (from the firmware's theme.slint). Dark stage-like theme;
 * red is reserved for the piano's own LEDs (hints) so the screen and the
 * keys speak the same language.
 */
object C {
    val bg = Color(0xFF0E1116)
    val surface = Color(0xFF161B22)
    val surface2 = Color(0xFF1D242D)
    val raised = Color(0xFF252D38)
    val border = Color(0xFF2B3440)
    val text = Color(0xFFE9EEF4)
    val dim = Color(0xFF8D99A6)
    val faint = Color(0xFF5B6673)
    val led = Color(0xFFFF3B4F)
    val accent = Color(0xFFFF5A6A)
    val right = Color(0xFF34D3C2)
    val left = Color(0xFF9B87FF)
    val pressed = Color(0xFF4DA3FF)
    val ok = Color(0xFF4ADE80)
    val warn = Color(0xFFFBBF24)
    val gold = Color(0xFFFFC94D)
    val paper = Color(0xFFFBF8F1)
    val ink = Color(0xFF1D2128)
}

@Composable
fun CoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            onPrimary = Color.White,
            secondary = C.right,
            background = C.bg,
            surface = C.surface,
            surfaceVariant = C.surface2,
            onSurface = C.text,
            onSurfaceVariant = C.dim,
            onBackground = C.text,
            outline = C.border,
        ),
        content = content,
    )
}

/** A row of mutually exclusive choices; the prominent control style. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    color: (T) -> Color = { C.accent },
    icon: (@Composable (T, Boolean) -> Unit)? = null,
) {
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(C.surface2)
            .border(1.dp, C.border, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (o in options) {
            val on = o == selected
            Box(
                Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) color(o) else Color.Transparent)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    icon?.invoke(o, on)
                    Text(
                        label(o),
                        color = if (on) Color.White else C.dim,
                        fontSize = 15.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Small rounded chip (connection state, loop pass …). */
@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = color, fontSize = 14.sp, maxLines = 1)
    }
}
