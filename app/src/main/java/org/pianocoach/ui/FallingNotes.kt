package org.pianocoach.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.pianocoach.Snapshot
import org.pianocoach.core.Hand
import org.pianocoach.core.HandSel
import org.pianocoach.core.KEY_HI
import org.pianocoach.core.KEY_LO
import org.pianocoach.core.Mode
import org.pianocoach.core.Song
import org.pianocoach.core.isBlack
import org.pianocoach.core.keyName
import org.pianocoach.core.partition

/** Song ms shown above the keyboard. */
private const val LOOKAHEAD_MS = 3000f

/** Key range [lo, hi] (both white keys) and each key's x/width for a width. */
class KeyLayout(val lo: Int, val hi: Int, width: Float) {
    val x = FloatArray(128)
    val w = FloatArray(128)
    val whiteW: Float

    init {
        val whites = (lo..hi).count { !isBlack(it) }
        whiteW = width / whites
        var i = 0
        for (k in lo..hi) {
            if (!isBlack(k)) {
                x[k] = i * whiteW
                w[k] = whiteW
                i++
            }
        }
        val bw = whiteW * 0.62f
        for (k in lo..hi) if (isBlack(k)) {
            // Between its white neighbours, nudged like a real keyboard.
            val nudge = when (k % 12) {
                1, 6 -> -0.1f
                3, 10 -> 0.1f
                else -> 0f
            }
            x[k] = x[k - 1] + whiteW - bw / 2 + nudge * bw
            w[k] = bw
        }
    }

    fun centre(k: Int) = x[k] + w[k] / 2

    /** Key under (px, py) on a keyboard of height h; black keys first. */
    fun hit(px: Float, py: Float, h: Float): Int? {
        if (py < h * 0.62f) {
            for (k in lo..hi) if (isBlack(k) && px >= x[k] && px < x[k] + w[k]) return k
        }
        for (k in lo..hi) if (!isBlack(k) && px >= x[k] && px < x[k] + w[k]) return k
        return null
    }

    companion object {
        /** The song's range padded to whole white keys, at least two octaves. */
        fun forSong(song: Song?, fit: Boolean): Pair<Int, Int> {
            if (song == null || !fit || song.notes.isEmpty()) return KEY_LO to KEY_HI
            var lo = song.notes.minOf { it.key } - 2
            var hi = song.notes.maxOf { it.key } + 2
            while (hi - lo < 28) {
                lo--; hi++
            }
            lo = lo.coerceAtLeast(KEY_LO)
            hi = hi.coerceAtMost(KEY_HI)
            while (isBlack(lo)) lo--
            while (isBlack(hi)) hi++
            return lo to hi
        }
    }
}

/** Per-frame key colouring shared by the roll and the keyboard. */
class KeyColors(snap: Snapshot, song: Song?, learner: Boolean, hands: HandSel) {
    /** Hand of a note sounding at the play position, or null. */
    val active = arrayOfNulls<Hand>(128)

    init {
        if (song != null) {
            val pos = snap.pos
            val first = partition(song.notes.size) { song.notes[it].startMs < pos - 20_000 }
            for (i in first until song.notes.size) {
                val n = song.notes[i]
                if (n.startMs > pos) break
                if (n.endMs > pos && !(learner && hands.includes(n.hand))) active[n.key] = n.hand
            }
        }
    }
}

private fun handColor(h: Hand) = if (h == Hand.Right) C.right else C.left

/**
 * Falling notes above an 88-key (or song-range) keyboard. Notes fall
 * towards the now-line; red bars are what to play now (the piano's LEDs
 * light the same keys).
 */
@Composable
fun FallingNotes(
    snap: Snapshot,
    song: Song?,
    mode: Mode,
    hands: HandSel,
    range: Pair<Int, Int>,
    names: Boolean,
    keyboardHeight: Dp,
    onKey: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val learner = mode != Mode.Listen
    val colors = KeyColors(snap, song, learner, hands)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val lay = KeyLayout(range.first, range.second, size.width)
            drawRoll(lay, snap, song, learner, hands, names)
        }
        Keyboard(snap, colors, range, names, onKey, Modifier.fillMaxWidth().height(keyboardHeight))
    }
}

private fun DrawScope.drawRoll(
    lay: KeyLayout,
    snap: Snapshot,
    song: Song?,
    learner: Boolean,
    hands: HandSel,
    names: Boolean,
) {
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0D12), Color(0xFF121822))))
    // Lanes: a line at every C and F, a shade under the black keys.
    for (k in lay.lo..lay.hi) {
        if (isBlack(k)) {
            drawRect(Color(0x0DFFFFFF), Offset(lay.x[k], 0f), Size(lay.w[k], h))
        } else if (k % 12 == 0 || k % 12 == 5) {
            drawLine(if (k % 12 == 0) Color(0x33FFFFFF) else Color(0x14FFFFFF), Offset(lay.x[k], 0f), Offset(lay.x[k], h), 1f)
        }
    }
    if (song == null) return
    val pos = snap.pos.toFloat()
    val nowY = h - 2f
    val ppm = nowY / LOOKAHEAD_MS
    val radius = CornerRadius(minOf(6f, lay.whiteW * 0.25f))
    val label = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        textSize = minOf(lay.whiteW * 0.5f, 28f)
        typeface = Typeface.DEFAULT_BOLD
        color = android.graphics.Color.argb(220, 10, 14, 20)
    }
    val first = partition(song.notes.size) { song.notes[it].startMs < pos - 20_000 }
    for (i in first until song.notes.size) {
        val n = song.notes[i]
        if (n.startMs > pos + LOOKAHEAD_MS) break
        if (n.endMs < pos - 50 || n.key < lay.lo || n.key > lay.hi) continue
        val yBottom = nowY - (n.startMs - pos) * ppm
        val yTop = nowY - (n.endMs - pos) * ppm
        val mine = learner && hands.includes(n.hand)
        val now = mine && snap.stepMs == n.startMs && n.key in snap.stepKeys
        val sounding = n.startMs <= pos && n.endMs > pos
        val base = handColor(n.hand)
        val fill = when {
            now -> C.led
            mine && n.startMs < pos - 1 -> base.copy(alpha = 0.45f)
            !learner && sounding -> base
            learner && !mine -> base.copy(alpha = 0.35f) // accompaniment
            else -> base.copy(alpha = 0.85f)
        }
        val inset = if (isBlack(n.key)) 0.5f else 1.5f
        val top = yTop.coerceAtLeast(-10f)
        val bottom = minOf(yBottom, nowY)
        if (bottom - top < 1f) continue
        val x = lay.x[n.key] + inset
        val w = (lay.w[n.key] - 2 * inset).coerceAtLeast(3f)
        drawRoundRect(fill, Offset(x, top), Size(w, bottom - top), radius)
        if (now) drawRoundRect(Color.White.copy(alpha = 0.8f), Offset(x, top), Size(w, bottom - top), radius, style = Stroke(2f))
        if (names && bottom - top > label.textSize * 1.3f && w > label.textSize * 0.9f) {
            drawContext.canvas.nativeCanvas.drawText(
                keyName(n.key).dropLast(1), lay.centre(n.key), bottom - label.textSize * 0.35f, label,
            )
        }
    }
    // Now-line.
    drawLine(C.led.copy(alpha = 0.8f), Offset(0f, nowY), Offset(size.width, nowY), 3f)
}

/**
 * The keyboard: LEDs (red hints), your presses (blue), wrong notes
 * (amber), notes the song is sounding (hand colours). Multi-touch: each
 * finger plays a key and can slide to the next.
 */
@Composable
fun Keyboard(
    snap: Snapshot,
    colors: KeyColors,
    range: Pair<Int, Int>,
    names: Boolean,
    onKey: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val held = remember { HashMap<PointerId, Int>() }
    val label = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            color = C.faint.toArgb()
        }
    }
    Canvas(
        modifier.pointerInput(range) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    val lay = KeyLayout(range.first, range.second, size.width.toFloat())
                    for (c in ev.changes) {
                        val prev = held[c.id]
                        val key = if (c.pressed) lay.hit(c.position.x, c.position.y, size.height.toFloat()) else null
                        if (key != prev) {
                            if (prev != null) onKey(prev, false)
                            if (key != null) onKey(key, true)
                            if (key == null) held.remove(c.id) else held[c.id] = key
                        }
                        c.consume()
                    }
                }
            }
        },
    ) {
        val lay = KeyLayout(range.first, range.second, size.width)
        val h = size.height
        val wrongFresh = SystemClock.uptimeMillis() - snap.wrongAt < 450
        fun fill(k: Int, black: Boolean): Color = when {
            wrongFresh && k == snap.wrongKey && snap.pressed[k] -> C.warn
            snap.pressed[k] -> C.pressed
            snap.hint[k] -> C.led
            colors.active[k] != null -> handColor(colors.active[k]!!)
            else -> if (black) Color(0xFF1A1E24) else Color(0xFFF4F1EA)
        }
        drawRect(Color(0xFF05070A))
        for (k in lay.lo..lay.hi) if (!isBlack(k)) {
            drawRoundRect(fill(k, false), Offset(lay.x[k] + 1f, 0f), Size(lay.w[k] - 2f, h - 1f), CornerRadius(4f))
            if (snap.hint[k] && (snap.pressed[k] || colors.active[k] != null)) {
                // LED still on under a pressed / sounding key: red base strip.
                drawRect(C.led, Offset(lay.x[k] + 1f, h - h * 0.12f), Size(lay.w[k] - 2f, h * 0.12f))
            }
            if (names && (k % 12 == 0 || lay.whiteW > 26f)) {
                label.textSize = minOf(lay.whiteW * 0.42f, 26f)
                label.color = (if (k % 12 == 0) C.ink else C.faint).toArgb()
                val t = if (k % 12 == 0) keyName(k) else keyName(k).dropLast(1)
                drawContext.canvas.nativeCanvas.drawText(t, lay.centre(k), h - h * 0.06f, label)
            }
        }
        val bh = h * 0.62f
        for (k in lay.lo..lay.hi) if (isBlack(k)) {
            drawRoundRect(fill(k, true), Offset(lay.x[k], 0f), Size(lay.w[k], bh), CornerRadius(3f))
            if (snap.hint[k] && (snap.pressed[k] || colors.active[k] != null)) {
                drawRect(C.led, Offset(lay.x[k], bh - bh * 0.14f), Size(lay.w[k], bh * 0.14f))
            }
        }
        // Felt strip.
        drawRect(Brush.verticalGradient(listOf(Color(0xFF7A1020), Color(0xFF3A0810))), Offset.Zero, Size(size.width, 4f))
    }
}

@Composable
fun KeyboardOnly(
    snap: Snapshot,
    song: Song?,
    mode: Mode,
    hands: HandSel,
    range: Pair<Int, Int>,
    names: Boolean,
    onKey: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Keyboard(snap, KeyColors(snap, song, mode != Mode.Listen, hands), range, names, onKey, modifier.fillMaxSize())
}
