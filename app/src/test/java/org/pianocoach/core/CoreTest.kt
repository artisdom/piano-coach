package org.pianocoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreTest {
    /** Format-0 file: C4 E4 together, then G4; 480 ppq, 120 bpm. */
    private fun tiny(): ByteArray {
        val trk = byteArrayOf(
            0x00, 0x90.toByte(), 60, 80, 0x00, 64, 80,
            0x83.toByte(), 0x60, 0x80.toByte(), 60, 0, 0x00, 0x80.toByte(), 64, 0,
            0x00, 0x90.toByte(), 67, 80, 0x83.toByte(), 0x60, 0x80.toByte(), 67, 0,
            0x00, 0xff.toByte(), 0x2f, 0x00,
        )
        val hdr = byteArrayOf(0x4d, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xe0.toByte())
        val th = byteArrayOf(0x4d, 0x54, 0x72, 0x6b, 0, 0, 0, trk.size.toByte())
        return hdr + th + trk
    }

    @Test
    fun parsesAndTimesNotes() {
        val s = Song.parse(tiny())
        assertEquals(3, s.notes.size)
        assertEquals(0, s.notes[0].startMs)
        assertEquals(500, s.notes[2].startMs)
        assertEquals(1000, s.durationMs)
        assertEquals(listOf(60, 64), s.steps(HandSel.Both)[0].keys)
    }

    @Test
    fun learnWaitsForChord() {
        val s = Song.parse(tiny())
        val e = Engine(s, Options(mode = Mode.Learn, accompany = false))
        val out = MsgList()
        e.play(0, out)
        assertEquals(PlayState.Waiting, e.state)
        assertTrue(e.isHint(60) && e.isHint(64))
        e.tick(2000, out)
        assertEquals(0, e.positionMs) // still waiting
        e.keyDown(60, out)
        e.keyDown(64, out)
        assertEquals(PlayState.Playing, e.state)
        e.tick(2001, out)
        e.tick(2600, out)
        assertEquals(PlayState.Waiting, e.state)
        assertEquals(listOf(67), e.waitingKeys())
        e.keyDown(61, out)
        assertEquals(1, e.stats.wrong)
    }

    @Test
    fun engravesTiny() {
        val score = Notation.engrave(Song.parse(tiny()), 1000f, 10f)
        assertEquals(1, score.systems.size)
        assertTrue(score.systems[0].items.any { it.noteKey == 67 })
    }

    /** Every song in the bundled library parses, plays in Listen and engraves. */
    @Test
    fun wholeLibrary() {
        val dir = File(System.getProperty("midiLibrary") ?: "../../midi-piano/assets/midi")
        if (!dir.isDirectory) {
            println("library not found at $dir; skipped")
            return
        }
        var n = 0
        var notes = 0L
        val t0 = System.currentTimeMillis()
        var slowest = 0L to ""
        dir.walkTopDown().filter { it.isFile && Names.isMidi(it.name) }.forEach { f ->
            val song = try {
                Song.parse(f.readBytes())
            } catch (e: SmfException) {
                println("skip ${f.name}: ${e.message}")
                return@forEach
            }
            notes += song.notes.size
            val t = System.currentTimeMillis()
            val score = Notation.engrave(song, 1200f, 12f)
            val dt = System.currentTimeMillis() - t
            if (dt > slowest.first) slowest = dt to f.name
            if (song.notes.isNotEmpty()) assertTrue(f.name, score.systems.isNotEmpty())
            // Cursor can be found throughout.
            for (ms in 0..song.durationMs step 997) score.locate(ms)
            // Learn engine runs to the end when every chord is played.
            val e = Engine(song, Options(mode = Mode.Learn))
            val out = MsgList()
            var now = 0L
            e.play(now, out)
            var guard = 0
            while (e.state != PlayState.Finished && guard++ < 200_000) {
                now += 50
                e.tick(now, out)
                for (k in e.waitingKeys()) e.keyDown(k, out)
                out.clear()
            }
            assertEquals(f.name, PlayState.Finished, e.state)
            n++
        }
        println("library: $n songs, $notes notes in ${System.currentTimeMillis() - t0} ms; slowest engrave ${slowest.first} ms (${slowest.second})")
        assertTrue(n > 100)
    }

    /** Right hand C5 every 500 ms for 10 s (vel 80), left hand C3 at the same times (vel 60). */
    private fun steady(): Song {
        val trk = ArrayList<Byte>()
        fun b(vararg v: Int) = v.forEach { trk.add(it.toByte()) }
        for (i in 0 until 20) {
            b(if (i == 0) 0 else 0x81, if (i == 0) 0 else 0x70) // delta 240 (0x81 0x70)
            if (i == 0) trk.removeAt(trk.size - 1)
            b(0x90, 72, 80, 0x00, 0x90, 48, 60)
            b(0x81, 0x70, 0x80, 72, 0, 0x00, 0x80, 48, 0)
        }
        b(0x00, 0xff, 0x2f, 0x00)
        val hdr = byteArrayOf(0x4d, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xe0.toByte())
        val n = trk.size
        val th = byteArrayOf(0x4d, 0x54, 0x72, 0x6b, 0, 0, (n shr 8).toByte(), (n and 0xff).toByte())
        return Song.parse(hdr + th + trk.toByteArray())
    }

    private fun struck(out: List<Int>, key: Int) =
        out.filter { (it shr 16) == 0x90 && ((it shr 8) and 0x7f) == key && (it and 0x7f) > 1 }.map { it and 0x7f }

    private fun MsgList.drainTo(all: MutableList<Int>) {
        for (i in 0 until size) all.add(this[i])
        clear()
    }

    @Test
    fun followListensUntilYouJoinIn() {
        val s = steady()
        assertEquals(40, s.notes.size)
        val e = Engine(s, Options(mode = Mode.Follow, leadMs = 400))
        val out = MsgList()
        val all = ArrayList<Int>()
        e.play(0, out)
        var t = 0L
        while (t < 3000) { t += 5; e.tick(t, out) }
        out.drainTo(all)
        // The hands are set by pitch: C5 right, C3 left; both at full velocity.
        assertTrue(struck(all, 72).all { it == 80 })
        assertTrue(struck(all, 48).all { it == 60 })
        assertEquals(0, e.stats.misses)
        assertEquals(false to false, e.following())
    }

    @Test
    fun followGuidesTheHandYouPlayThenLetsGo() {
        val e = Engine(steady(), Options(mode = Mode.Follow, leadMs = 400))
        val out = MsgList()
        val all = ArrayList<Int>()
        e.play(0, out)
        e.keyDown(72, out) // join in with the right hand, play until 1.5 s
        var t = 0L
        while (t < 9000) {
            t += 5
            e.tick(t, out)
            if (t % 500 == 0L && t < 2000) e.keyDown(72, out)
        }
        out.drainTo(all)
        val rh = struck(all, 72)
        assertEquals(80, rh[0]) // struck before the first hit
        // 0.5 … 5 s soft (4 s after the last hit at 1.5 s), then full again.
        assertTrue(rh.toString(), rh.subList(1, 11).all { it == 32 })
        assertTrue(rh.toString(), rh.subList(11, rh.size).all { it == 80 })
        assertTrue(struck(all, 48).all { it == 60 })
        assertEquals(false to false, e.following())
    }

    @Test
    fun controlKeysYieldToTheSong() {
        assertEquals(Control.PrevSong, ControlKeys.of(21))
        assertEquals(Control.SetMode(Mode.Follow), ControlKeys.of(105))
        assertEquals(null, ControlKeys.of(60))
        assertEquals(ControlKeys.KEYS.size, ControlKeys.KEYS.map { it.first }.toSet().size)
        assertTrue(ControlKeys.KEYS.all { it.first in 21..108 })
        // Left hand: A0 at the start, F1 at 10 s. Mid-song a control key is a
        // note only near a note of that key, in a hand the learner plays.
        val notes = listOf(Note(0, 500, 21, 60, 0, Hand.Left), Note(10_000, 10_500, 29, 60, 0, Hand.Left))
        assertEquals(Control.PrevSong, ControlKeys.press(21, null))
        assertEquals(null, ControlKeys.press(21, notes, 0, HandSel.Both))
        assertEquals(Control.PrevSong, ControlKeys.press(21, notes, ControlKeys.NOTE_BEFORE_MS + 1, HandSel.Both))
        assertEquals(Control.PrevSong, ControlKeys.press(21, notes, 0, HandSel.Right))
        assertEquals(Control.NextCategory, ControlKeys.press(29, notes, 0, HandSel.Both))
        assertEquals(null, ControlKeys.press(29, notes, 7_500, HandSel.Left))
        assertEquals(2_500, ControlKeys.noteNear(notes, 29, 7_500, HandSel.Left))
        assertEquals(Control.NextCategory, ControlKeys.press(29, notes, 10_000 + ControlKeys.NOTE_BEFORE_MS + 1, HandSel.Both))
        assertEquals(Control.NextSong, ControlKeys.press(23, notes, 0, HandSel.Both))
    }

    @Test
    fun shufflePlaysEachSongOnceCurrentFirst() {
        val songs = (0 until 10).map { "s$it.mid" }
        val sh = Shuffle(songs, "s7.mid", 42)
        assertEquals("s7.mid", sh.current)
        val seen = mutableListOf(sh.current!!)
        while (true) seen.add(sh.step(1) ?: break)
        assertEquals(songs, seen.sorted())
        assertEquals(10, sh.played)
        val b = Shuffle(songs, null, 1)
        assertEquals(b.current, b.step(-1))
    }
}
