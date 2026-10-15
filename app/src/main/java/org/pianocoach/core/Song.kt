package org.pianocoach.core

/**
 * A song as the player sees it: timed notes with a hand assignment, plus
 * the pedal controls worth forwarding to the piano.
 *
 * Port of piano-core `song.rs`.
 */
const val KEY_LO = 21
const val KEY_HI = 108

/** Onsets closer than this are one chord ("step") for learning. */
const val CHORD_WINDOW_MS = 40

enum class Hand { Right, Left }

/** Which notes the learner plays. */
enum class HandSel {
    Both, Right, Left;

    fun includes(h: Hand) = when (this) {
        Both -> true
        Right -> h == Hand.Right
        Left -> h == Hand.Left
    }
}

class Note(
    val startMs: Int,
    val endMs: Int,
    val key: Int,
    val vel: Int,
    val track: Int,
    val hand: Hand,
)

/** Sustain/soft pedal changes (CC 64 / 66 / 67), in ms. */
class Pedal(val ms: Int, val cc: Int, val v: Int)

enum class SplitRule { TrackNames, TrackPitch, MiddleC }

/** Musical time: where the beats and bars are (for sheet music). */
class Meter(
    /** Tempo segments: (song ms, quarter notes, ms per quarter), by ms. */
    val tempo: List<DoubleArray>,
    /** Time signatures: (quarter-note position, numerator, denominator). */
    val timeSigs: List<Triple<Double, Int, Int>>,
    /** Key signature: sharps (> 0) or flats (< 0). */
    val key: Int,
    val keyDeclared: Boolean,
) {
    /** Quarter notes elapsed at song time [ms]. */
    fun qAt(ms: Double): Double {
        val i = partition(tempo.size) { tempo[it][0] <= ms } - 1
        if (i < 0) return if (tempo.isEmpty()) ms / 500.0 else tempo[0].let { it[1] + (ms - it[0]) / it[2] }
        val s = tempo[i]
        return s[1] + (ms - s[0]) / s[2]
    }

    /** Song ms at quarter-note position [q]. */
    fun msAt(q: Double): Double {
        val i = partition(tempo.size) { tempo[it][1] <= q } - 1
        if (i < 0) return if (tempo.isEmpty()) q * 500.0 else tempo[0].let { it[0] + (q - it[1]) * it[2] }
        val s = tempo[i]
        return s[0] + (q - s[1]) * s[2]
    }
}

/** First index in 0 until n for which pred is false (pred must be monotone). */
inline fun partition(n: Int, pred: (Int) -> Boolean): Int {
    var lo = 0
    var hi = n
    while (lo < hi) {
        val m = (lo + hi) ushr 1
        if (pred(m)) lo = m + 1 else hi = m
    }
    return lo
}

/** The key signature whose major scale leaves the fewest notes needing an accidental. */
fun estimateKey(notes: List<Note>): Int {
    val hist = IntArray(12)
    for (n in notes) hist[n.key % 12]++
    val scale = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    var best = Int.MAX_VALUE
    var bestK = 0
    for (k in -6..6) {
        val tonic = Math.floorMod(7 * k, 12)
        var outside = 0
        for (pc in 0 until 12) if ((pc + 12 - tonic) % 12 !in scale) outside += hist[pc]
        if (outside < best || (outside == best && Math.abs(k) < Math.abs(bestK))) {
            best = outside
            bestK = k
        }
    }
    return bestK
}

/** One learning step: the keys to press together at [ms]. */
class Step(val ms: Int, val keys: MutableList<Int>)

private fun nameHand(name: String): Hand? {
    val n = name.lowercase()
    val words = n.split(Regex("[^a-z0-9]+"))
    fun has(w: String) = words.contains(w)
    return when {
        n.contains("right") || has("rh") || has("r") || name.contains('右') -> Hand.Right
        n.contains("left") || has("lh") || has("l") || name.contains('左') -> Hand.Left
        else -> null
    }
}

class Song(
    /** Sorted by startMs, then key. */
    val notes: List<Note>,
    val pedals: List<Pedal>,
    val durationMs: Int,
    val bpm: Double,
    val noteTracks: Int,
    val split: SplitRule,
    val meter: Meter,
) {
    /** Start of the latest note at or before [ms] (0 if none). */
    fun onsetAtOrBefore(ms: Int): Int {
        val i = partition(notes.size) { notes[it].startMs <= ms }
        return if (i == 0) 0 else notes[i - 1].startMs
    }

    /** Group the selected hand's notes into chords to press together. */
    fun steps(hands: HandSel): List<Step> {
        val steps = ArrayList<Step>()
        for (n in notes) {
            if (!hands.includes(n.hand)) continue
            val s = steps.lastOrNull()
            if (s != null && n.startMs - s.ms <= CHORD_WINDOW_MS) {
                if (n.key !in s.keys) s.keys.add(n.key)
            } else {
                steps.add(Step(n.startMs, mutableListOf(n.key)))
            }
        }
        return steps
    }

    val rightCount: Int get() = notes.count { it.hand == Hand.Right }
    val leftCount: Int get() = notes.count { it.hand == Hand.Left }

    companion object {
        fun parse(bytes: ByteArray): Song = fromSmf(SmfParser.parse(bytes))

        fun fromSmf(smf: Smf): Song {
            val tempo = TempoMap(smf)
            class Raw(val start: Double, val end: Double, val key: Int, val vel: Int, val track: Int)

            val raw = ArrayList<Raw>()
            val pedals = ArrayList<Pedal>()
            val trackIds = ArrayList<Int>()
            val names = ArrayList<String>()

            for ((ti0, track) in smf.tracks.withIndex()) {
                val ti = minOf(ti0, 255)
                // Notes still held: (ch·128 + key, start ms, velocity), in start order.
                val open = ArrayList<Triple<Int, Double, Int>>()
                var any = false
                for (e in track.events) {
                    when (val k = e.kind) {
                        is MidiKind.NoteOn -> {
                            // GM percussion has no place on a piano keyboard.
                            if (k.ch == 9) continue
                            open.add(Triple(k.ch * 128 + k.key, tempo.ms(e.tick), k.vel))
                        }
                        is MidiKind.NoteOff -> {
                            val slot = k.ch * 128 + k.key
                            val i = open.indexOfFirst { it.first == slot }
                            if (i >= 0) {
                                val o = open.removeAt(i)
                                raw.add(Raw(o.second, tempo.ms(e.tick), k.key, o.third, ti))
                                any = true
                            }
                        }
                        is MidiKind.Control -> if (k.ch != 9 && (k.cc == 64 || k.cc == 66 || k.cc == 67)) {
                            pedals.add(Pedal(Math.round(tempo.ms(e.tick)).toInt(), k.cc, k.v))
                        }
                        else -> {}
                    }
                }
                // Notes never released: end them at the track's last event.
                val last = track.events.lastOrNull()?.let { tempo.ms(it.tick) } ?: 0.0
                open.sortBy { it.first }
                for ((slot, start, vel) in open) {
                    raw.add(Raw(start, maxOf(last, start + 250.0), slot % 128, vel, ti))
                    any = true
                }
                if (any) {
                    trackIds.add(ti)
                    names.add(track.name)
                }
            }

            // Hand assignment.
            val named = names.map { nameHand(it) }
            val split: SplitRule
            val trackHand: List<Hand?>
            if (trackIds.size >= 2 && named.all { it != null }) {
                split = SplitRule.TrackNames
                trackHand = named
            } else if (trackIds.size >= 2) {
                val avg = trackIds.map { t ->
                    val ks = raw.filter { it.track == t }
                    ks.sumOf { it.key.toDouble() } / maxOf(1, ks.size)
                }
                split = SplitRule.TrackPitch
                trackHand = if (trackIds.size == 2) {
                    val right = if (avg[0] >= avg[1]) 0 else 1
                    listOf(0, 1).map { if (it == right) Hand.Right else Hand.Left }
                } else {
                    avg.map { if (it >= 60.0) Hand.Right else Hand.Left }
                }
            } else {
                split = SplitRule.MiddleC
                trackHand = trackIds.map { null }
            }

            val notes = raw.map { r ->
                val ti = trackIds.indexOf(r.track)
                val hand = (if (ti >= 0) trackHand[ti] else null)
                    ?: if (r.key >= 60) Hand.Right else Hand.Left
                // Fold anything outside the 88 keys into range by octaves.
                var key = r.key
                while (key < KEY_LO) key += 12
                while (key > KEY_HI) key -= 12
                val startMs = maxOf(0.0, Math.round(r.start).toDouble()).toInt()
                Note(startMs, maxOf(Math.round(r.end).toInt(), startMs + 20), key, r.vel, r.track, hand)
            }.sortedWith(compareBy({ it.startMs }, { it.key }))
            pedals.sortBy { it.ms }

            val durationMs = notes.maxOfOrNull { it.endMs } ?: 0
            val ppq = (smf.division as? Division.Ppq)?.ppq?.toDouble()
            val sigs = ArrayList<Triple<Long, Int, Int>>()
            var key: Int? = null
            for (e in smf.tracks.flatMap { it.events }) {
                when (val k = e.kind) {
                    is MidiKind.TimeSig -> sigs.add(Triple(e.tick, k.num, k.den))
                    is MidiKind.KeySig -> if (key == null && e.tick == 0L) key = k.sf
                    else -> {}
                }
            }
            sigs.sortBy { it.first }
            val timeSigs = ArrayList<Triple<Double, Int, Int>>()
            if (ppq != null) {
                for ((tick, num, den) in sigs) {
                    val q = tick / ppq
                    val last = timeSigs.lastOrNull()
                    when {
                        last != null && Math.abs(last.first - q) < 1e-6 -> timeSigs[timeSigs.size - 1] = Triple(q, num, den)
                        last != null && last.second == num && last.third == den -> {}
                        else -> timeSigs.add(Triple(q, num, den))
                    }
                }
            }
            val meter = Meter(tempo.quarterSegments(), timeSigs, key ?: estimateKey(notes), key != null)
            return Song(notes, pedals, durationMs, tempo.initialBpm, trackIds.size, split, meter)
        }
    }
}

private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** "C4", "F#5" … */
fun keyName(key: Int): String = "${NAMES[key % 12]}${key / 12 - 1}"

fun isBlack(key: Int): Boolean = when (key % 12) {
    1, 3, 6, 8, 10 -> true
    else -> false
}

/** "3:07" for a duration in ms. */
fun fmtTime(ms: Int): String {
    val s = maxOf(0, ms) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
