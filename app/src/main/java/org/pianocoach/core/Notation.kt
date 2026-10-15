package org.pianocoach.core

/**
 * Sheet music from a [Song]: a grand staff (right hand on the treble staff,
 * left hand on the bass staff) engraved into lines of measures ("systems")
 * as simple drawing primitives the UI renders. Port of piano-core
 * `notation.rs`.
 *
 * Practical notation for a learner, not a full engraver: onsets and
 * durations snap to a 16th grid; one voice per staff (a chord lasts until
 * the staff's next onset); notes crossing a barline are split and tied;
 * gaps become rests; accidentals follow the key signature and carry through
 * the measure; eighths and sixteenths are beamed per beat; spacing grows
 * with duration and every system is justified to the full width.
 *
 * Coordinates are pixels in the system's own frame; `space` is the staff
 * space (distance between two staff lines).
 */

/** Grid: 16th notes. */
private const val GRID = 4.0

/**
 * Music font glyphs (Unicode musical symbols, as in Noto Music). The font's
 * baseline is a staff's bottom line and a staff space is ¼ em.
 */
enum class Glyph(val ch: String, val width: Float) {
    GClef("𝄞", 2.84f),
    FClef("𝄢", 3.16f),
    Sharp("♯", 2.21f),
    Flat("♭", 1.39f),
    Natural("♮", 1.43f),
    HeadBlack("𝅘", 1.59f),
    HeadHalf("𝅗", 1.59f),
    HeadWhole("𝅝", 2.06f),
    RestWhole("𝄻", 3.13f),
    RestHalf("𝄼", 3.13f),
    RestQuarter("𝄽", 1.43f),
    Rest8("𝄾", 1.45f),
    Rest16("𝄿", 1.72f);

    val isHead get() = this == HeadBlack || this == HeadHalf || this == HeadWhole
}

sealed class Shape {
    /** Filled rectangle: staff lines, stems, ledgers, barlines, beams. */
    class Rect(val x: Float, val y: Float, val w: Float, val h: Float) : Shape()

    /** A music glyph with its baseline-left origin at (x, y). */
    class Sym(val g: Glyph, val x: Float, val y: Float) : Shape()

    /** Augmentation dot centred at (x, y). */
    class Dot(val x: Float, val y: Float) : Shape()

    /** [n] flags hanging from a stem tip at (x, y). */
    class Flag(val x: Float, val y: Float, val up: Boolean, val n: Int) : Shape()

    /** Tie from x0 to x1 at height y, curving up or down. */
    class Tie(val x0: Float, val x1: Float, val y: Float, val up: Boolean) : Shape()

    /** Time-signature digits centred at (x, y). */
    class Digits(val text: String, val x: Float, val y: Float) : Shape()
}

/**
 * [noteMs]/[noteKey]: note heads carry their note's start and key (-1 for
 * tied continuations and everything else). [staff]: heads give the hand
 * (0 right, 1 left), other items the staff.
 */
class Item(val shape: Shape, val noteMs: Int, val noteKey: Int, val staff: Int)

class SystemLine(
    val items: List<Item>,
    val height: Float,
    /** Song ms → x anchors (ascending), for the play cursor. */
    val anchorMs: IntArray,
    val anchorX: FloatArray,
    val ms0: Int,
    val ms1: Int,
    /** Vertical extent of the staves (cursor), in system coordinates. */
    val top: Float,
    val bottom: Float,
)

class Score(val systems: List<SystemLine>) {
    /** System index and x of the cursor at song time [ms]. */
    fun locate(ms: Int): Pair<Int, Float>? {
        if (systems.isEmpty()) return null
        val i = maxOf(0, partition(systems.size) { systems[it].ms0 <= ms } - 1)
        val am = systems[i].anchorMs
        val ax = systems[i].anchorX
        val j = partition(am.size) { am[it] <= ms }
        val x = when {
            j > 0 && j < am.size && am[j] > am[j - 1] ->
                ax[j - 1] + (ax[j] - ax[j - 1]) * (ms - am[j - 1]).toFloat() / (am[j] - am[j - 1])
            j > 0 -> ax[j - 1]
            j < am.size -> ax[j]
            else -> 0f
        }
        return i to x
    }
}

// ---- musical model ------------------------------------------------------------

private class Measure(val q0: Double, val len: Double, val num: Int, val den: Int, val showSig: Boolean)

/** A note value: base length in quarters (4, 2, 1, ½, ¼) and a dot. */
private data class Value(val base: Double, val dot: Boolean) {
    val len get() = if (dot) base * 1.5 else base
}

private val VALUES = listOf(
    4.0 to false, 2.0 to true, 2.0 to false, 1.0 to true, 1.0 to false,
    0.5 to true, 0.5 to false, 0.25 to true, 0.25 to false,
)

/** Split [len] quarters into note values, longest first. */
private fun values(len0: Double): List<Value> {
    var len = len0
    val out = ArrayList<Value>()
    while (len > 0.2) {
        val (base, dot) = VALUES.firstOrNull { (b, d) -> b * (if (d) 1.5 else 1.0) <= len + 1e-9 } ?: break
        val v = Value(base, dot)
        out.add(v)
        len -= v.len
    }
    return out
}

private class ChordNote(val key: Int, val ms: Int, val hand: Int)

private class Ev(
    /** Quarters from the measure start. */
    val off: Double,
    val staff: Int,
    val value: Value,
    // Chord (notes != null) or rest.
    val notes: List<ChordNote>?,
    val tiedIn: Boolean = false,
    val tiedOut: Boolean = false,
    val wholeMeasure: Boolean = false,
)

private fun quant(q: Double) = Math.round(q * GRID) / GRID

private val SHARP_SPELL = arrayOf(0 to 0, 0 to 1, 1 to 0, 1 to 1, 2 to 0, 3 to 0, 3 to 1, 4 to 0, 4 to 1, 5 to 0, 5 to 1, 6 to 0)
private val FLAT_SPELL = arrayOf(0 to 0, 1 to -1, 1 to 0, 2 to -1, 2 to 0, 3 to 0, 4 to -1, 4 to 0, 5 to -1, 5 to 0, 6 to -1, 6 to 0)

/** Diatonic step (C4 = 0) and alteration of [key]. */
private fun spell(key: Int, flats: Boolean): Pair<Int, Int> {
    val (letter, alter) = (if (flats) FLAT_SPELL else SHARP_SPELL)[key % 12]
    val octave = key / 12 - 1
    return ((octave - 4) * 7 + letter) to alter
}

/** Letter (0 = C … 6 = B) and alteration of [key] written in key signature [keySig]. */
fun spelling(key: Int, keySig: Int): Pair<Int, Int> {
    val (step, alter) = spell(key, keySig < 0)
    return Math.floorMod(step, 7) to alter
}

/** Alteration the key signature gives a letter (0 = C … 6 = B). */
private fun keyAlter(key: Int, letter: Int): Int {
    val sharps = intArrayOf(3, 0, 4, 1, 5, 2, 6) // F C G D A E B
    val flats = intArrayOf(6, 2, 5, 1, 4, 0, 3) // B E A D G C F
    return when {
        key > 0 && (0 until key).any { sharps[it] == letter } -> 1
        key < 0 && (0 until -key).any { flats[it] == letter } -> -1
        else -> 0
    }
}

private fun measures(song: Song, endQ: Double): List<Measure> {
    val sigs = song.meter.timeSigs.ifEmpty { listOf(Triple(0.0, 4, 4)) }
    val out = ArrayList<Measure>()
    var q = 0.0
    var si = 0
    var num = 4
    var den = 4
    var shown = 0 to 0
    // A signature takes effect at the first barline at or after it.
    while (q < endQ - 1e-9 && out.size < 100_000) {
        while (si < sigs.size && sigs[si].first <= q + 1e-6) {
            num = sigs[si].second
            den = sigs[si].third
            si++
        }
        val len = maxOf(1.0, Math.round(num * 4.0 / den * GRID).toDouble()) / GRID
        out.add(Measure(q, len, num, den, (num to den) != shown))
        shown = num to den
        q += len
    }
    return out
}

/** Beat length (quarters) for beaming in a measure. */
private fun beat(m: Measure): Double = when {
    m.den == 8 && m.num % 3 == 0 && m.num > 3 -> 1.5
    m.den >= 8 -> 0.5 * 8.0 / m.den * 2.0
    else -> 1.0
}

/** Group the song into measure events per staff. */
private fun events(song: Song, ms: List<Measure>): List<MutableList<Ev>> {
    val perMeasure = List(ms.size) { ArrayList<Ev>() }
    fun measureAt(q: Double) = maxOf(0, partition(ms.size) { ms[it].q0 <= q + 1e-9 } - 1)
    val flatsKey = song.meter.key < 0
    // Each hand on its staff, unless a note is far outside it.
    fun staffOf(n: Note): Int {
        val step = spell(n.key, flatsKey).first
        return when {
            n.hand == Hand.Right && step < -3 -> 1
            n.hand == Hand.Left && step > 3 -> 0
            n.hand == Hand.Right -> 0
            else -> 1
        }
    }
    for (staff in 0..1) {
        // (onset, end, notes) per distinct onset.
        class Chord(val on: Double, var off: Double, val notes: MutableList<ChordNote>)
        val chords = ArrayList<Chord>()
        for (n in song.notes) {
            if (staffOf(n) != staff) continue
            val hand = if (n.hand == Hand.Right) 0 else 1
            val on = quant(song.meter.qAt(n.startMs.toDouble()))
            val off = maxOf(quant(song.meter.qAt(n.endMs.toDouble())), on + 1.0 / GRID)
            val c = chords.lastOrNull { Math.abs(it.on - on) < 1e-9 }
            if (c != null) {
                c.off = maxOf(c.off, off)
                if (c.notes.none { it.key == n.key }) c.notes.add(ChordNote(n.key, n.startMs, hand))
            } else {
                chords.add(Chord(on, off, mutableListOf(ChordNote(n.key, n.startMs, hand))))
            }
        }
        chords.sortBy { it.on }
        // One voice: a chord ends where the next one starts.
        for (i in chords.indices) {
            if (i + 1 < chords.size) chords[i].off = minOf(chords[i].off, chords[i + 1].on)
            chords[i].notes.sortBy { it.key }
        }
        // Pieces within measures, tied across barlines.
        val busy = List(ms.size) { ArrayList<Pair<Double, Double>>() }
        for (c in chords) {
            var q = c.on
            var first = true
            while (q < c.off - 1e-9) {
                val mi = measureAt(q)
                val m = ms[mi]
                val end = minOf(c.off, m.q0 + m.len)
                if (end <= q + 1e-9) break // past the last barline (rounding)
                val vals = values(end - q)
                var o = q - m.q0
                for ((k, v) in vals.withIndex()) {
                    val lastPiece = end >= c.off - 1e-9 && k == vals.size - 1
                    perMeasure[mi].add(Ev(o, staff, v, c.notes, tiedIn = !first, tiedOut = !lastPiece))
                    first = false
                    o += v.len
                }
                busy[mi].add((q - m.q0) to (end - m.q0))
                q = end
            }
        }
        // Rests in the gaps.
        for ((mi, m) in ms.withIndex()) {
            val b = busy[mi].sortedBy { it.first }
            if (b.isEmpty()) {
                perMeasure[mi].add(Ev(0.0, staff, Value(minOf(m.len, 4.0), false), null, wholeMeasure = true))
                continue
            }
            var t = 0.0
            for ((s, e) in b + listOf(m.len to m.len)) {
                if (s > t + 1e-9) {
                    var o = t
                    for (v in values(s - t)) {
                        perMeasure[mi].add(Ev(o, staff, v, null))
                        o += v.len
                    }
                }
                t = maxOf(t, e)
            }
        }
    }
    for (e in perMeasure) e.sortWith(compareBy<Ev>({ it.off }, { it.staff }))
    return perMeasure
}

// ---- engraving ----------------------------------------------------------------

private class Geo(val s: Float, val yt: Float, val yb: Float) {
    /** y of a diatonic step on a staff. */
    fun y(staff: Int, step: Int): Float =
        if (staff == 0) yt + (10 - step) * s / 2f // top line F5 = step 10
        else yb + (-2 - step) * s / 2f // top line A3 = step -2

    fun bottomLine(staff: Int) = (if (staff == 0) yt else yb) + 4f * s

    companion object {
        fun middle(staff: Int) = if (staff == 0) 6 else -6
    }
}

/** Horizontal gap after an onset that lasts [dq] quarters (in spaces). */
private fun gap(dq: Double): Float = 2.6f + (Math.log(maxOf(dq, 0.25) / 0.25) / Math.log(2.0)).toFloat() * 1.1f

private class MeasureLayout(m: Measure, ev: List<Ev>, acc: (Int) -> Boolean) {
    /** Distinct onsets (quarters from the measure start). */
    val onsets = ArrayList<Double>()
    val fixed = ArrayList<Float>()
    val gaps = ArrayList<Float>()
    val lead: Float = if (m.showSig) 1.2f + 2.6f else 1.2f

    init {
        for (e in ev) if (onsets.isEmpty() || Math.abs(onsets.last() - e.off) >= 1e-9) onsets.add(e.off)
        for ((i, o) in onsets.withIndex()) {
            val next = onsets.getOrElse(i + 1) { m.len }
            val here = ev.indices.filter { Math.abs(ev[it].off - o) < 1e-9 }
            val wide = here.any { ev[it].value.base >= 4.0 }
            val accidental = here.any { acc(it) }
            val dotted = here.any { ev[it].value.dot }
            fixed.add(if (accidental) 1.6f else 0f)
            gaps.add(gap(next - o) + (if (wide) 0.6f else 0f) + (if (dotted) 0.6f else 0f))
        }
    }

    val natural: Float get() = lead + fixed.sum() + gaps.sum()

    fun indexOf(off: Double): Int = onsets.indexOfFirst { Math.abs(it - off) < 1e-9 }.coerceAtLeast(0)
}

object Notation {
    /** Engrave [song] into systems [width] px wide with staff space [space] px. */
    fun engrave(song: Song, width: Float, space: Float): Score {
        if (song.notes.isEmpty()) return Score(emptyList())
        val s = space
        val meter = song.meter
        // As in `events`: every note lasts at least one grid step.
        val endQ = song.notes.maxOf {
            maxOf(quant(meter.qAt(it.endMs.toDouble())), quant(meter.qAt(it.startMs.toDouble())) + 1.0 / GRID)
        }
        val ms = measures(song, endQ)
        val ev = events(song, ms)
        val key = meter.key
        val flats = key < 0

        // Accidentals per measure (state per staff + step, reset each measure).
        val accOf = ev.map { evs ->
            val state = HashMap<Long, Int>()
            evs.map { e ->
                val notes = e.notes ?: return@map emptyList<Glyph?>()
                notes.map { n ->
                    val (step, alter) = spell(n.key, flats)
                    val k = e.staff.toLong() * 1000 + step + 500
                    val cur = state[k] ?: keyAlter(key, Math.floorMod(step, 7))
                    if (e.tiedIn || alter == cur) {
                        null
                    } else {
                        state[k] = alter
                        when (alter) {
                            1 -> Glyph.Sharp
                            -1 -> Glyph.Flat
                            else -> Glyph.Natural
                        }
                    }
                }
            }
        }

        val layouts = ms.indices.map { mi ->
            MeasureLayout(ms[mi], ev[mi]) { i -> accOf[mi].getOrNull(i)?.any { it != null } == true }
        }

        // Header: clefs + key signature.
        val header = 0.6f + 3.4f + Math.abs(key) * 1.0f + 0.8f
        val avail = width / s - header - 0.5f
        val ranges = ArrayList<IntRange>()
        var i = 0
        while (i < ms.size) {
            var w = 0f
            var j = i
            while (j < ms.size && (j == i || w + layouts[j].natural <= avail)) {
                w += layouts[j].natural
                j++
            }
            ranges.add(i until j)
            i = j
        }

        // Room for ledger lines: 3.5 spaces above the treble staff, 6 between
        // the staves, 3 below the bass staff.
        val geo = Geo(s, 3.5f * s, 3.5f * s + 4f * s + 6f * s)
        val height = geo.yb + 4f * s + 3f * s
        fun msOf(q: Double) = Math.round(maxOf(0.0, meter.msAt(q))).toInt()

        val systems = ArrayList<SystemLine>()
        for (r in ranges) {
            val items = ArrayList<Item>()
            fun add(shape: Shape, staff: Int, noteMs: Int = -1, noteKey: Int = -1) =
                items.add(Item(shape, noteMs, noteKey, staff))

            val line = 0.11f * s
            // Staves.
            for (staff in 0..1) {
                val y0 = if (staff == 0) geo.yt else geo.yb
                for (k in 0 until 5) add(Shape.Rect(0f, y0 + k * s - line / 2f, width, line), staff)
            }
            // System barline + clefs + key signature.
            val sysTop = geo.yt
            val sysBot = geo.yb + 4f * s
            add(Shape.Rect(0f, sysTop, 0.3f * s, sysBot - sysTop), 0)
            add(Shape.Sym(Glyph.GClef, 0.6f * s, geo.bottomLine(0) - 1f * s), 0)
            add(Shape.Sym(Glyph.FClef, 0.6f * s, geo.bottomLine(1) - 1f * s), 1)
            var x = (0.6f + 3.4f) * s
            val sh = intArrayOf(10, 7, 11, 8, 5, 9, 6)
            val fl = intArrayOf(6, 9, 5, 8, 4, 7, 3)
            for (k in 0 until Math.abs(key)) {
                val st = if (key > 0) sh[k] else fl[k]
                val g = if (key > 0) Glyph.Sharp else Glyph.Flat
                for (staff in 0..1) {
                    val step = if (staff == 0) st else st - 14
                    add(Shape.Sym(g, x, accBaseline(geo, staff, step, g)), staff)
                }
                x += 1f * s
            }
            val xStart = header * s

            // Justify: stretch only the gaps.
            val natural = r.sumOf { layouts[it].natural.toDouble() }.toFloat()
            val gaps = r.sumOf { layouts[it].gaps.sum().toDouble() }.toFloat()
            val lastSystem = r.last == ms.size - 1
            val room = (width / s - header - 0.5f) - natural
            // A short last system is not stretched beyond 1.6×.
            val k = if (gaps > 0f) {
                val kk = 1f + room / gaps
                if (lastSystem) minOf(kk, 1.6f) else kk
            } else 1f

            val anchors = ArrayList<Pair<Int, Float>>()
            var mx = xStart
            for (mi in r) {
                val m = ms[mi]
                val lay = layouts[mi]
                val evs = ev[mi]
                val accs = accOf[mi]
                if (m.showSig) {
                    val tx = mx + 1.4f * s
                    for (staff in 0..1) {
                        val top = if (staff == 0) geo.yt else geo.yb
                        add(Shape.Digits(m.num.toString(), tx, top + 1f * s), staff)
                        add(Shape.Digits(m.den.toString(), tx, top + 3f * s), staff)
                    }
                }
                // Onset x positions.
                val ox = FloatArray(lay.onsets.size)
                var cx = mx + lay.lead * s
                for (oi in lay.onsets.indices) {
                    cx += lay.fixed[oi] * s
                    ox[oi] = cx
                    cx += lay.gaps[oi] * k * s
                }
                val mEnd = cx
                for ((oi, o) in lay.onsets.withIndex()) anchors.add(msOf(m.q0 + o) to ox[oi])

                // Beam groups per staff: runs of flagged chords within a beat.
                val bt = beat(m)
                val beamed = BooleanArray(evs.size)
                for (staff in 0..1) {
                    val groups = ArrayList<List<Int>>()
                    var g = ArrayList<Int>()
                    for (ei in evs.indices) {
                        val e = evs[ei]
                        if (e.staff != staff) continue
                        val flagged = e.notes != null && e.value.base <= 0.5
                        val sameBeat = g.isNotEmpty() && Math.floor(evs[g[0]].off / bt) == Math.floor(e.off / bt)
                        if (!(flagged && (g.isEmpty() || sameBeat))) {
                            groups.add(g)
                            g = ArrayList()
                        }
                        if (flagged) g.add(ei)
                    }
                    groups.add(g)
                    for (gr in groups) if (gr.size >= 2) drawBeamed(gr, evs, lay, ox, geo, flats, items, beamed)
                }

                for ((ei, e) in evs.withIndex()) {
                    val oi = lay.indexOf(e.off)
                    val ex = ox[oi]
                    val notes = e.notes
                    if (notes == null) {
                        val g: Glyph
                        val xx: Float
                        if (e.wholeMeasure) {
                            g = Glyph.RestWhole
                            xx = (mx + mEnd) / 2f - 1.5f * s
                        } else {
                            g = restGlyph(e.value.base)
                            xx = ex
                        }
                        add(Shape.Sym(g, xx, restBaseline(geo, e.staff, g)), e.staff)
                        if (e.value.dot && !e.wholeMeasure) {
                            add(Shape.Dot(xx + g.width * s + 0.6f * s, geo.y(e.staff, Geo.middle(e.staff) + 1)), e.staff)
                        }
                    } else {
                        val nextX = if (oi + 1 < ox.size) ox[oi + 1] else mEnd + lay.lead * s
                        drawChord(items, geo, e, notes, accs[ei], ex, flats, beamed[ei], nextX)
                    }
                }
                // Barline at the measure's end.
                add(Shape.Rect(mEnd - 0.06f * s, sysTop, 0.12f * s, sysBot - sysTop), 0)
                anchors.add(msOf(m.q0 + m.len) to mEnd)
                mx = mEnd
            }
            val ms0 = msOf(ms[r.first].q0)
            val ms1 = msOf(ms[r.last].q0 + ms[r.last].len)
            val sorted = anchors.sortedBy { it.first }.distinctBy { it.first }
            systems.add(
                SystemLine(
                    items, height,
                    IntArray(sorted.size) { sorted[it].first },
                    FloatArray(sorted.size) { sorted[it].second },
                    ms0, ms1, sysTop - 1.5f * s, sysBot + 1.5f * s,
                )
            )
        }
        return Score(systems)
    }

    private fun restGlyph(base: Double) = when {
        base >= 4.0 -> Glyph.RestWhole
        base >= 2.0 -> Glyph.RestHalf
        base >= 1.0 -> Glyph.RestQuarter
        base >= 0.5 -> Glyph.Rest8
        else -> Glyph.Rest16
    }

    /** Baseline for a rest glyph (baseline = bottom line in the font). */
    private fun restBaseline(g: Geo, staff: Int, glyph: Glyph): Float {
        val bottom = g.bottomLine(staff)
        return when (glyph) {
            Glyph.RestWhole -> bottom - 1f * g.s // hangs from the 4th line
            Glyph.RestHalf -> bottom - 2f * g.s // sits on the 3rd
            else -> bottom - 0.5f * g.s
        }
    }

    /** Baseline for an accidental glyph so it centres on [step]. */
    private fun accBaseline(g: Geo, staff: Int, step: Int, glyph: Glyph): Float {
        val y = g.y(staff, step)
        return if (glyph == Glyph.Flat) y + 0.55f * g.s else y + 0.4f * g.s
    }

    private fun headGlyph(v: Value) = when {
        v.base >= 4.0 -> Glyph.HeadWhole
        v.base >= 2.0 -> Glyph.HeadHalf
        else -> Glyph.HeadBlack
    }

    private fun stemUp(staff: Int, steps: List<Int>): Boolean {
        val mid = Geo.middle(staff)
        val lo = steps.minOrNull() ?: mid
        val hi = steps.maxOrNull() ?: mid
        // The note farther from the middle line decides.
        return (mid - lo) >= (hi - mid)
    }

    /** Heads, ledgers, accidentals, dots, stem, flags and ties of one chord. */
    private fun drawChord(
        items: MutableList<Item>,
        g: Geo,
        e: Ev,
        notes: List<ChordNote>,
        accs: List<Glyph?>,
        x: Float,
        flats: Boolean,
        beamed: Boolean,
        nextX: Float,
    ) {
        val s = g.s
        val head = headGlyph(e.value)
        val hw = head.width * s
        val steps = notes.map { spell(it.key, flats).first }
        val up = stemUp(e.staff, steps)
        fun add(shape: Shape, staff: Int, ms: Int = -1, key: Int = -1) = items.add(Item(shape, ms, key, staff))

        // Seconds: alternate heads to the other side of the stem.
        val dx = FloatArray(notes.size)
        for (i in 1 until steps.size) {
            if (steps[i] - steps[i - 1] == 1 && dx[i - 1] == 0f) {
                if (up) dx[i] = hw - 0.1f * s else dx[i - 1] = -(hw - 0.1f * s)
            }
        }

        val ledger = 0.11f * s
        val maxDx = dx.fold(0f) { a, b -> maxOf(a, b) }
        for ((i, n) in notes.withIndex()) {
            val step = steps[i]
            val y = g.y(e.staff, step)
            val hx = x + dx[i]
            // Ledger lines.
            val top = if (e.staff == 0) 10 else -2
            val bottom = if (e.staff == 0) 2 else -10
            var l = top + 2
            while (l <= step) {
                add(Shape.Rect(hx - 0.4f * s, g.y(e.staff, l) - ledger / 2f, hw + 0.8f * s, ledger), e.staff)
                l += 2
            }
            l = bottom - 2
            while (l >= step) {
                add(Shape.Rect(hx - 0.4f * s, g.y(e.staff, l) - ledger / 2f, hw + 0.8f * s, ledger), e.staff)
                l -= 2
            }
            // Head: glyph spans 0..1.08 spaces above its baseline.
            if (e.tiedIn) add(Shape.Sym(head, hx, y + 0.54f * s), n.hand)
            else add(Shape.Sym(head, hx, y + 0.54f * s), n.hand, n.ms, n.key)
            accs.getOrNull(i)?.let { a ->
                add(Shape.Sym(a, x - a.width * s - 0.3f * s + minOf(dx[i], 0f), accBaseline(g, e.staff, step, a)), e.staff)
            }
            if (e.value.dot) {
                // In the space above when the head is on a line.
                val dy = if (Math.floorMod(step, 2) == 0) -0.5f * s else 0f
                add(Shape.Dot(x + hw + 0.5f * s + maxDx, y + dy), e.staff)
            }
            if (e.tiedOut) {
                val tx0 = hx + hw + 0.1f * s
                val tx1 = maxOf(nextX - 0.1f * s, tx0 + 1f * s)
                val ty = if (up) y + 0.9f * s else y - 0.9f * s
                add(Shape.Tie(tx0, tx1, ty, !up), e.staff)
            }
        }

        // Stem + flags (beamed chords get theirs from drawBeamed).
        if (e.value.base < 4.0 && !beamed) {
            val lo = g.y(e.staff, steps.min())
            val hi = g.y(e.staff, steps.max())
            val len = 3.5f * s
            val sw = 0.13f * s
            val nFlags = if (e.value.base <= 0.25) 2 else 1
            if (up) {
                val sx = x + hw - sw
                add(Shape.Rect(sx, hi - len, sw, lo - (hi - len)), e.staff)
                if (e.value.base <= 0.5) add(Shape.Flag(sx + sw, hi - len, true, nFlags), e.staff)
            } else {
                add(Shape.Rect(x, hi, sw, lo + len - hi), e.staff)
                if (e.value.base <= 0.5) add(Shape.Flag(x, lo + len, false, nFlags), e.staff)
            }
        }
    }

    /** Stems and beams for a group of flagged chords (indices into [evs]). */
    private fun drawBeamed(
        group: List<Int>,
        evs: List<Ev>,
        lay: MeasureLayout,
        ox: FloatArray,
        g: Geo,
        flats: Boolean,
        items: MutableList<Item>,
        beamed: BooleanArray,
    ) {
        val s = g.s
        val staff = evs[group[0]].staff
        fun chordSteps(i: Int) = evs[i].notes?.map { spell(it.key, flats).first } ?: emptyList()
        fun add(shape: Shape) = items.add(Item(shape, -1, -1, staff))
        val all = group.flatMap { chordSteps(it) }
        val up = stemUp(staff, all)
        val hw = Glyph.HeadBlack.width * s
        val sw = 0.13f * s
        fun xOf(i: Int) = ox[lay.indexOf(evs[i].off)]
        // Horizontal beam beyond the most extreme head.
        val beamY = if (up) {
            group.minOf { g.y(staff, chordSteps(it).max()) } - 3.2f * s
        } else {
            group.maxOf { g.y(staff, chordSteps(it).min()) } + 3.2f * s
        }
        val bh = 0.5f * s
        fun stemX(i: Int) = if (up) xOf(i) + hw - sw else xOf(i)
        for (i in group) {
            val st = chordSteps(i)
            val lo = g.y(staff, st.min())
            val hi = g.y(staff, st.max())
            val sx = stemX(i)
            if (up) add(Shape.Rect(sx, beamY, sw, lo - beamY)) else add(Shape.Rect(sx, hi, sw, beamY - hi))
            beamed[i] = true
        }
        val x0 = stemX(group[0])
        val x1 = stemX(group.last()) + sw
        val by = if (up) beamY else beamY - bh
        add(Shape.Rect(x0, by, x1 - x0, bh))
        // Second beam for runs of 16ths; a lone 16th gets a stub.
        val by2 = if (up) by + 0.75f * s else by - 0.75f * s
        var k = 0
        while (k < group.size) {
            if (evs[group[k]].value.base > 0.25) {
                k++
                continue
            }
            var j = k
            while (j + 1 < group.size && evs[group[j + 1]].value.base <= 0.25) j++
            val (a, b) = when {
                j > k -> stemX(group[k]) to stemX(group[j]) + sw
                k + 1 < group.size -> stemX(group[k]) to stemX(group[k]) + 1.2f * s
                else -> (stemX(group[k]) - 1.2f * s + sw) to (stemX(group[k]) + sw)
            }
            add(Shape.Rect(a, by2, b - a, bh))
            k = j + 1
        }
    }
}
