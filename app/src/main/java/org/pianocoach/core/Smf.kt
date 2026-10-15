package org.pianocoach.core

/**
 * Standard MIDI File reader: formats 0/1/2, PPQ or SMPTE timing, running
 * status, SysEx and meta events. Produces per-track events on absolute
 * ticks plus a merged tempo map; [Song] turns that into timed notes.
 *
 * Port of piano-core `smf.rs`.
 */
class SmfException(msg: String) : Exception(msg)

sealed class Division {
    /** Ticks per quarter note; tempo meta events give µs per quarter. */
    data class Ppq(val ppq: Int) : Division()

    /** Frames per second × ticks per frame — absolute, tempo-independent. */
    data class Smpte(val fps: Int, val ticksPerFrame: Int) : Division()
}

sealed class MidiKind {
    data class NoteOn(val ch: Int, val key: Int, val vel: Int) : MidiKind()
    data class NoteOff(val ch: Int, val key: Int) : MidiKind()
    data class Control(val ch: Int, val cc: Int, val v: Int) : MidiKind()
    data class Program(val ch: Int, val program: Int) : MidiKind()

    /** µs per quarter note. */
    data class Tempo(val us: Int) : MidiKind()
    data class TimeSig(val num: Int, val den: Int) : MidiKind()

    /** Sharps (> 0) or flats (< 0). */
    data class KeySig(val sf: Int, val minor: Boolean) : MidiKind()
}

class MidiEvent(val tick: Long, val kind: MidiKind)

class Track(var name: String = "", val events: MutableList<MidiEvent> = ArrayList())

class Smf(val format: Int, val division: Division, val tracks: List<Track>)

private class Reader(val b: ByteArray, var i: Int = 0, val end: Int = b.size) {
    fun u8(): Int {
        if (i >= end) throw SmfException("file is truncated")
        return b[i++].toInt() and 0xff
    }

    fun peek(): Int {
        if (i >= end) throw SmfException("file is truncated")
        return b[i].toInt() and 0xff
    }

    fun skip(n: Int) {
        if (n < 0 || i + n > end) throw SmfException("file is truncated")
        i += n
    }

    fun take(n: Int): ByteArray {
        if (n < 0 || i + n > end) throw SmfException("file is truncated")
        val r = b.copyOfRange(i, i + n)
        i += n
        return r
    }

    fun be(n: Int): Long {
        var v = 0L
        repeat(n) { v = (v shl 8) or u8().toLong() }
        return v
    }

    /** Variable-length quantity (max 4 bytes per spec; tolerate a 5th). */
    fun vlq(): Long {
        var v = 0L
        repeat(5) {
            val c = u8()
            v = (v shl 7) or (c and 0x7f).toLong()
            if (c and 0x80 == 0) return v
        }
        return v
    }
}

/**
 * Decode track-name bytes. Many library files store GBK; only the ASCII
 * words (left/right/piano) matter for hand detection, so non-UTF-8 names
 * keep their ASCII characters and drop the rest.
 */
private fun decodeName(d: ByteArray): String {
    val dec = Charsets.UTF_8.newDecoder()
    return try {
        dec.decode(java.nio.ByteBuffer.wrap(d)).toString().trim('\u0000').trim()
    } catch (e: java.nio.charset.CharacterCodingException) {
        d.filter { it >= 0x20 }.map { it.toInt().toChar() }.joinToString("").trim()
    }
}

private fun startsWith(b: ByteArray, at: Int, s: String): Boolean {
    if (at + s.length > b.size) return false
    for (k in s.indices) if (b[at + k].toInt() != s[k].code) return false
    return true
}

object SmfParser {
    fun parse(bytes: ByteArray): Smf {
        val r = Reader(bytes)
        // RIFF-wrapped MIDI (RMID) — skip to the embedded MThd.
        if (startsWith(bytes, 0, "RIFF")) {
            val pos = (0..bytes.size - 4).firstOrNull { startsWith(bytes, it, "MThd") }
                ?: throw SmfException("not a MIDI file")
            r.i = pos
        }
        if (!startsWith(bytes, r.i, "MThd")) throw SmfException("not a MIDI file")
        r.skip(4)
        val hlen = r.be(4).toInt()
        val header = r.take(hlen)
        if (header.size < 6) throw SmfException("file is truncated")
        fun h16(i: Int) = ((header[i].toInt() and 0xff) shl 8) or (header[i + 1].toInt() and 0xff)
        val format = h16(0)
        val ntracks = h16(2)
        val div = h16(4)
        val division = if (div and 0x8000 != 0) {
            val fps = -((div shr 8).toByte().toInt())
            val tpf = div and 0xff
            if (fps <= 0 || tpf == 0) throw SmfException("unsupported time division")
            Division.Smpte(fps, tpf)
        } else {
            if (div == 0) throw SmfException("unsupported time division")
            Division.Ppq(div)
        }
        val tracks = ArrayList<Track>(ntracks)
        while (tracks.size < ntracks && r.i + 8 <= bytes.size) {
            val isTrk = startsWith(bytes, r.i, "MTrk")
            r.skip(4)
            val len = r.be(4)
            // A truncated last chunk is common in the wild: parse what exists.
            val avail = minOf(len, (bytes.size - r.i).toLong()).toInt()
            val start = r.i
            r.skip(avail)
            if (!isTrk) continue // unknown chunk types must be skipped
            tracks.add(parseTrack(bytes, start, start + avail))
        }
        if (tracks.isEmpty()) throw SmfException("file is truncated")
        return Smf(format, division, tracks)
    }

    /** Parse one MTrk body. Stops quietly at the first malformed byte. */
    private fun parseTrack(bytes: ByteArray, from: Int, to: Int): Track {
        val r = Reader(bytes, from, to)
        val t = Track()
        var tick = 0L
        var status = 0
        try {
            loop@ while (r.i < to) {
                tick += r.vlq()
                var c = r.peek()
                if (c >= 0x80) {
                    r.i++
                } else if (status == 0) {
                    break // data byte with no status
                } else {
                    c = status // running status
                }
                when {
                    c == 0xff -> {
                        val ty = r.u8()
                        val len = r.vlq().toInt()
                        val d = r.take(len)
                        fun u(i: Int) = d[i].toInt() and 0xff
                        when {
                            ty == 0x2f -> break@loop
                            ty == 0x51 && d.size == 3 -> {
                                val us = (u(0) shl 16) or (u(1) shl 8) or u(2)
                                if (us > 0) t.events.add(MidiEvent(tick, MidiKind.Tempo(us)))
                            }
                            ty == 0x58 && d.size >= 2 && u(0) > 0 && u(1) <= 6 ->
                                t.events.add(MidiEvent(tick, MidiKind.TimeSig(u(0), 1 shl u(1))))
                            ty == 0x59 && d.size >= 2 && d[0].toInt() in -7..7 ->
                                t.events.add(MidiEvent(tick, MidiKind.KeySig(d[0].toInt(), u(1) == 1)))
                            // Only the first name counts.
                            ty == 0x03 && t.name.isEmpty() -> t.name = decodeName(d)
                        }
                        // Meta and SysEx events cancel running status.
                        status = 0
                    }
                    c == 0xf0 || c == 0xf7 -> {
                        r.skip(r.vlq().toInt())
                        status = 0
                    }
                    c in 0x80..0xef -> {
                        status = c
                        val ch = c and 0x0f
                        val d1 = r.u8() and 0x7f
                        val hi = c and 0xf0
                        val d2 = if (hi == 0xc0 || hi == 0xd0) 0 else r.u8() and 0x7f
                        val kind = when {
                            hi == 0x90 && d2 > 0 -> MidiKind.NoteOn(ch, d1, d2)
                            hi == 0x90 || hi == 0x80 -> MidiKind.NoteOff(ch, d1)
                            hi == 0xb0 -> MidiKind.Control(ch, d1, d2)
                            hi == 0xc0 -> MidiKind.Program(ch, d1)
                            else -> null // aftertouch, pitch bend: not used
                        }
                        if (kind != null) t.events.add(MidiEvent(tick, kind))
                    }
                    // System common/real-time bytes do not belong in files.
                    else -> {}
                }
            }
        } catch (_: SmfException) {
            // keep what was decoded
        }
        return t
    }
}

/**
 * Tick → milliseconds converter built from every tempo event in the file
 * (format-1 files may place tempo changes outside track 0).
 */
class TempoMap(smf: Smf) {
    private val division = smf.division

    /** (tick, ms at that tick, µs per quarter from that tick on) */
    private val ticks = ArrayList<Long>()
    private val msAt = ArrayList<Double>()
    private val usq = ArrayList<Int>()

    init {
        val changes = smf.tracks.flatMap { it.events }
            .mapNotNull { e -> (e.kind as? MidiKind.Tempo)?.let { e.tick to it.us } }
            .sortedBy { it.first }
        ticks.add(0); msAt.add(0.0); usq.add(500_000)
        for ((tick, us) in changes) {
            val last = ticks.size - 1
            val ms = msAt[last] + spanMs(tick - ticks[last], usq[last])
            if (tick == ticks[last]) {
                usq[last] = us
            } else {
                ticks.add(tick); msAt.add(ms); usq.add(us)
            }
        }
    }

    private fun spanMs(t: Long, usPerQ: Int): Double = when (val d = division) {
        is Division.Ppq -> t.toDouble() * usPerQ / d.ppq / 1000.0
        is Division.Smpte -> {
            // 29 means 29.97 drop-frame.
            val fps = if (d.fps == 29) 29.97 else d.fps.toDouble()
            t.toDouble() * 1000.0 / (fps * d.ticksPerFrame)
        }
    }

    fun ms(tick: Long): Double {
        var lo = 0
        var hi = ticks.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (ticks[m] <= tick) lo = m + 1 else hi = m
        }
        val idx = maxOf(0, lo - 1)
        return msAt[idx] + spanMs(tick - ticks[idx], usq[idx])
    }

    /**
     * Tempo segments as (song ms, quarter notes, ms per quarter). Files
     * timed in SMPTE frames have no beats: they get a nominal 120 bpm.
     */
    fun quarterSegments(): List<DoubleArray> = when (val d = division) {
        is Division.Ppq -> ticks.indices.map {
            doubleArrayOf(msAt[it], ticks[it].toDouble() / d.ppq, usq[it] / 1000.0)
        }
        is Division.Smpte -> listOf(doubleArrayOf(0.0, 0.0, 500.0))
    }

    val initialBpm: Double get() = 60_000_000.0 / usq[0]
}
