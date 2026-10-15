package org.pianocoach.midi

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** What the piano sends us. Called on a link thread. */
interface PianoListener {
    fun onKey(key: Int, down: Boolean, vel: Int)

    /** Vendor panel SysEx `F0 52 4B pp vv 00 00 F7` (volume, tone …). */
    fun onPanel(param: Int, value: Int) {}
    fun onLinkState(link: PianoLink, connected: Boolean, detail: String)
}

/**
 * A connection to the GZUT piano. Outgoing messages are packed channel
 * messages (status << 16 | d1 << 8 | d2) queued here and written by the
 * transport's own sender thread, which paces them as the transport needs.
 */
abstract class PianoLink(val kind: String) {
    @Volatile
    var connected = false
        protected set

    @Volatile
    var detail = ""
        protected set

    protected val queue = LinkedBlockingQueue<Int>()

    fun send(m: Int) {
        if (connected) queue.offer(m)
    }

    abstract fun close()

    /** Take up to [max] messages; waits up to [waitMs] for the first. */
    protected fun drain(max: Int, waitMs: Long): IntArray? {
        val first = queue.poll(waitMs, TimeUnit.MILLISECONDS) ?: return null
        val out = ArrayList<Int>(max)
        out.add(first)
        while (out.size < max) out.add(queue.poll() ?: break)
        return out.toIntArray()
    }

    protected fun encode(msgs: IntArray, prefix: ByteArray = ByteArray(0)): ByteArray {
        val b = ByteArray(prefix.size + msgs.size * 3)
        prefix.copyInto(b)
        var i = prefix.size
        for (m in msgs) {
            b[i++] = (m shr 16).toByte()
            b[i++] = (m shr 8).toByte()
            b[i++] = m.toByte()
        }
        return b
    }
}

/**
 * MIDI byte-stream decoder for what the piano sends: running status,
 * SysEx, and (for BLE) timestamp bytes — a byte ≥ 0x80 directly followed by
 * another ≥ 0x80 is a timestamp (the piano always sends full messages).
 */
class MidiStreamParser(private val l: PianoListener, private val ble: Boolean) {
    private var status = 0
    private val data = IntArray(2)
    private var n = 0
    private var sysex: java.io.ByteArrayOutputStream? = null

    fun feed(b: ByteArray, off: Int, count: Int) {
        var i = off
        val end = off + count
        if (ble) {
            // BLE-MIDI: header byte first.
            i++
        }
        while (i < end) {
            val c = b[i].toInt() and 0xff
            if (ble && c >= 0x80 && i + 1 < end && (b[i + 1].toInt() and 0xff) >= 0x80 && sysex == null) {
                i++ // timestamp
                continue
            }
            val sx = sysex
            if (sx != null) {
                if (c == 0xf7) {
                    finishSysex(sx.toByteArray())
                    sysex = null
                } else if (c < 0x80) {
                    sx.write(c)
                } // timestamp bytes inside SysEx are skipped
                i++
                continue
            }
            when {
                c == 0xf0 -> sysex = java.io.ByteArrayOutputStream()
                c >= 0xf8 -> {} // real-time
                c >= 0x80 -> {
                    status = c; n = 0
                }
                status != 0 -> {
                    data[n++] = c
                    val need = if ((status and 0xf0) == 0xc0 || (status and 0xf0) == 0xd0) 1 else 2
                    if (n >= need) {
                        dispatch()
                        n = 0
                    }
                }
            }
            i++
        }
    }

    private fun dispatch() {
        when (status and 0xf0) {
            0x90 -> l.onKey(data[0], data[1] > 0, data[1])
            0x80 -> l.onKey(data[0], false, 0)
        }
    }

    private fun finishSysex(d: ByteArray) {
        if (d.size >= 4 && d[0].toInt() == 0x52 && d[1].toInt() == 0x4B) l.onPanel(d[2].toInt(), d[3].toInt())
    }
}
