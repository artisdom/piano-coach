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
}
