package org.pianocoach.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pianocoach.R
import org.pianocoach.Snapshot
import org.pianocoach.core.Glyph
import org.pianocoach.core.HandSel
import org.pianocoach.core.Mode
import org.pianocoach.core.Notation
import org.pianocoach.core.Score
import org.pianocoach.core.Shape
import org.pianocoach.core.Song
import org.pianocoach.core.partition
import org.pianocoach.core.spelling

/**
 * Pitch-class colours, C … B. Boomwhackers: C red, D orange, E yellow,
 * F lime, G green, A purple, B magenta, chromatic tubes in between (yellow
 * and lime darkened so they read on paper).
 */
private val BOOMWHACKERS = intArrayOf(
    0xE21C2B, 0xEE5A24, 0xF7941D, 0xF6B91D, 0xE3BE00, 0x8DBF22, 0x2EAA4A, 0x0B8A43, 0x0E9C9C, 0x6A3FA0, 0xA53C9E, 0xD8287F,
)

/** Rainbow (Chroma-Notes style): C red … G blue, A indigo, B violet. */
private val RAINBOW = intArrayOf(
    0xE53935, 0xF4511E, 0xFB8C00, 0xF6B400, 0xE0B400, 0x43A047, 0x00A39A, 0x1E88E5, 0x3D5AFE, 0x3F37C9, 0x7B3FBF, 0x9C27B0,
)
private val LETTERS = arrayOf("C", "D", "E", "F", "G", "A", "B")
private val SOLFEGE = arrayOf("Do", "Re", "Mi", "Fa", "So", "La", "Ti")

/** A system's height in staff spaces (grand staff + margins). */
private const val SYSTEM_SPACES = 20.5f

private fun opaque(c: Int) = c or (0xFF shl 24)

private fun isLight(c: Int): Boolean {
    val r = (c shr 16) and 255
    val g = (c shr 8) and 255
    val b = c and 255
    return (299 * r + 587 * g + 114 * b) / 1000 > 150
}

private class Engraved(val song: Song, val width: Float, val space: Float, val score: Score)

/**
 * Full-width grand staff, as many systems as fit, starting with the one
 * being played (the page turns when the cursor enters the next). Tap a
 * place in the music to jump there.
 */
@Composable
fun SheetView(
    snap: Snapshot,
    song: Song?,
    mode: Mode,
    hands: HandSel,
    colors: Int,
    names: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val music = remember { ResourcesCompat.getFont(ctx, R.font.noto_music) ?: Typeface.SERIF }
    var engraved by remember { mutableStateOf<Engraved?>(null) }
    BoxWithConstraints(modifier.background(C.paper)) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val margin = 18f * density
        // Two systems fill the height, within a readable range.
        val space = ((hPx - 12f * density) / (2f * SYSTEM_SPACES)).coerceIn(7f * density, 16f * density).let {
            Math.floor(it.toDouble()).toFloat()
        }
        val width = wPx - 2 * margin
        LaunchedEffect(song, width, space) {
            if (song == null) {
                engraved = null
                return@LaunchedEffect
            }
            val e = engraved
            if (e != null && e.song === song && e.width == width && e.space == space) return@LaunchedEffect
            val t0 = System.currentTimeMillis()
            val score = withContext(Dispatchers.Default) { Notation.engrave(song, width, space) }
            Log.i("Sheet", "${score.systems.size} systems in ${System.currentTimeMillis() - t0} ms")
            engraved = Engraved(song, width, space, score)
        }
        val e = engraved?.takeIf { it.song === song }
        if (song == null || e == null) {
            Text(
                if (song == null) "Pick a song in the Library to see its sheet music." else "Engraving…",
                color = C.ink, modifier = Modifier.align(Alignment.Center),
            )
            return@BoxWithConstraints
        }
        if (e.score.systems.isEmpty()) {
            Text("No notes to show.", color = C.ink, modifier = Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        val painter = remember(e) { SheetPainter(e.score, e.space, music, density) }
        val loc = e.score.locate(snap.pos)
        val first = loc?.first ?: 0
        val sysH = Math.round(e.score.systems[0].height).toFloat()
        val count = maxOf(1, ((hPx - 8f) / sysH).toInt())
        // Notes to play now: Learn's current chord, else the latest onset.
        val now = if (mode == Mode.Learn && snap.stepMs >= 0) snap.stepMs else song.onsetAtOrBefore(snap.pos)
        val lit = if (mode == Mode.Listen) HandSel.Both else hands
        Canvas(
            Modifier.fillMaxSize().pointerInput(e, first, count) {
                detectTapGestures { p ->
                    val i = first + ((p.y - 4f) / sysH).toInt()
                    val sys = e.score.systems.getOrNull(i) ?: return@detectTapGestures
                    val x = p.x - margin
                    val j = partition(sys.anchorX.size) { sys.anchorX[it] <= x }
                    val ms = sys.anchorMs.getOrNull(maxOf(0, j - 1)) ?: return@detectTapGestures
                    onSeek(ms)
                }
            },
        ) {
            painter.draw(this, first, count, margin, 4f, sysH, loc?.second, now, lit, colors, names, song.meter.key)
        }
    }
}

private class SheetPainter(val score: Score, val s: Float, music: Typeface, density: Float) {
    private val ink = C.ink.toArgb()
    private val fill = Paint().apply { isAntiAlias = true; color = ink }
    private val glyph = Paint().apply {
        isAntiAlias = true
        typeface = music
        textSize = 4f * s
        color = ink
    }
    private val rim = Paint(glyph).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.22f * s
        strokeJoin = Paint.Join.ROUND
    }
    private val halo = Paint().apply { isAntiAlias = true; color = C.led.copy(alpha = 0.35f).toArgb() }
    private val label = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 0.95f * s
    }
    private val digits = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSize = 2.5f * s
        color = ink
    }
    private val cursor = Paint().apply { color = C.pressed.copy(alpha = 0.28f).toArgb() }
    private val cursorLine = Paint().apply { color = C.pressed.toArgb(); strokeWidth = 2f * density / 2f }
    private val path = Path()
    private val rect = RectF()

    fun draw(
        d: DrawScope, first: Int, count: Int, ox: Float, oy0: Float, sysH: Float,
        cursorX: Float?, now: Int, hands: HandSel, colors: Int, names: Int, keySig: Int,
    ) {
        val c = d.drawContext.canvas.nativeCanvas
        val palette = when (colors) {
            1 -> BOOMWHACKERS
            2 -> RAINBOW
            else -> null
        }
        var oy = oy0
        for (si in first until minOf(score.systems.size, first + count)) {
            val sys = score.systems[si]
            if (si == first && cursorX != null) {
                rect.set(ox + cursorX - 0.9f * s, oy + sys.top, ox + cursorX + 0.9f * s, oy + sys.bottom)
                c.drawRoundRect(rect, 0.4f * s, 0.4f * s, cursor)
                c.drawLine(ox + cursorX, oy + sys.top, ox + cursorX, oy + sys.bottom, cursorLine)
            }
            for (it in sys.items) {
                when (val sh = it.shape) {
                    is Shape.Rect -> {
                        // Whole pixels: hairlines at fractional positions fade.
                        val x0 = Math.round(ox + sh.x).toFloat()
                        val y0 = Math.round(oy + sh.y).toFloat()
                        val x1 = maxOf(x0 + 1, Math.round(ox + sh.x + sh.w).toFloat())
                        val y1 = maxOf(y0 + 1, Math.round(oy + sh.y + sh.h).toFloat())
                        c.drawRect(x0, y0, x1, y1, fill)
                    }
                    is Shape.Sym -> {
                        val x = ox + sh.x
                        val y = oy + sh.y
                        if (sh.g.isHead && it.noteKey >= 0) {
                            drawHead(c, sh.g, x, y, it.noteKey, it.noteMs, it.staff, now, hands, palette, names, keySig)
                        } else {
                            c.drawText(sh.g.ch, x, y, glyph)
                        }
                    }
                    is Shape.Dot -> c.drawCircle(ox + sh.x, oy + sh.y, 0.22f * s, fill)
                    is Shape.Flag -> for (k in 0 until sh.n) {
                        val dy = k * 0.9f * s
                        flag(c, ox + sh.x, oy + if (sh.up) sh.y + dy else sh.y - dy, sh.up)
                    }
                    is Shape.Tie -> tie(c, ox + sh.x0, ox + sh.x1, oy + sh.y, sh.up)
                    is Shape.Digits -> {
                        val fm = digits.fontMetrics
                        c.drawText(sh.text, ox + sh.x, oy + sh.y - (fm.ascent + fm.descent) / 2f, digits)
                    }
                }
            }
            oy += sysH
        }
    }

    private fun drawHead(
        c: android.graphics.Canvas, g: Glyph, x: Float, y: Float, key: Int, ms: Int, hand: Int,
        now: Int, hands: HandSel, palette: IntArray?, names: Int, keySig: Int,
    ) {
        val handOk = when (hands) {
            HandSel.Both -> true
            HandSel.Right -> hand == 0
            HandSel.Left -> hand == 1
        }
        val current = ms == now && handOk
        val cx = x + g.width * s / 2f
        val cy = y - 0.54f * s
        if (current) c.drawCircle(cx, cy, 1.45f * s, halo)
        val col = when {
            palette != null -> opaque(palette[key % 12])
            current -> C.led.toArgb()
            else -> ink
        }
        val filled = g == Glyph.HeadBlack
        if (palette != null && filled) {
            // Dark rim so light colours read on paper.
            rim.color = ink
            c.drawText(g.ch, x, y, rim)
        }
        glyph.color = col
        c.drawText(g.ch, x, y, glyph)
        glyph.color = ink
        if (names != 0) {
            val (letter, _) = spelling(key, keySig)
            val t = if (names == 1) LETTERS[letter] else SOLFEGE[letter]
            label.textSize = if (t.length > 1) 0.78f * s else 0.95f * s
            label.color = when {
                !filled -> ink
                palette != null && isLight(palette[key % 12]) -> ink
                else -> android.graphics.Color.WHITE
            }
            val fm = label.fontMetrics
            c.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2f, label)
        }
    }

    /** One flag from a stem tip; [up] stems carry it downwards. */
    private fun flag(c: android.graphics.Canvas, x: Float, y: Float, up: Boolean) {
        val d = if (up) 1f else -1f
        path.reset()
        path.moveTo(x, y)
        path.cubicTo(x + 0.15f * s, y + d * 1.1f * s, x + 1.5f * s, y + d * 1.4f * s, x + 0.95f * s, y + d * 3.0f * s)
        path.cubicTo(x + 1.15f * s, y + d * 1.9f * s, x + 0.5f * s, y + d * 1.55f * s, x, y + d * 1.25f * s)
        path.close()
        c.drawPath(path, fill)
    }

    /** A tie as a filled crescent from x0 to x1. */
    private fun tie(c: android.graphics.Canvas, x0: Float, x1: Float, y: Float, up: Boolean) {
        val d = if (up) -1f else 1f
        val mx = (x0 + x1) / 2f
        val bulge = minOf(1.2f * s, 0.12f * (x1 - x0) + 0.5f * s)
        path.reset()
        path.moveTo(x0, y)
        path.quadTo(mx, y + d * (bulge + 0.18f * s), x1, y)
        path.quadTo(mx, y + d * bulge, x0, y)
        path.close()
        c.drawPath(path, fill)
    }
}
