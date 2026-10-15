package org.pianocoach.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * USB-MIDI through android.media.midi (the piano is class-compliant:
 * `GZU-tek CHMidi-2.3`, 5022:0716). The system MIDI service owns the USB
 * device, so no USB permission dialog is needed.
 *
 * The piano drops events from large bursts: at most 8 messages per write,
 * 12 ms apart while a backlog remains (all 88 LEDs light this way).
 */
class UsbMidiLink(
    private val context: Context,
    private val listener: PianoListener,
) : PianoLink("USB") {
    private val mm = context.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    private val handler = Handler(Looper.getMainLooper())
    private var device: MidiDevice? = null
    private var input: MidiInputPort? = null
    private var output: MidiOutputPort? = null
    private var info: MidiDeviceInfo? = null
    private var sender: Thread? = null

    @Volatile
    private var closed = false

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(d: MidiDeviceInfo) {
            if (device == null) tryOpen(d)
        }

        override fun onDeviceRemoved(d: MidiDeviceInfo) {
            if (d.id == info?.id) {
                disconnect("unplugged")
                // Another one may still be there.
                mm?.devices?.firstOrNull { usable(it) }?.let { tryOpen(it) }
            }
        }
    }

    val available get() = mm != null

    fun start() {
        val m = mm ?: return
        m.registerDeviceCallback(callback, handler)
        @Suppress("DEPRECATION")
        m.devices.firstOrNull { usable(it) }?.let { tryOpen(it) }
    }

    private fun usable(d: MidiDeviceInfo) =
        d.type == MidiDeviceInfo.TYPE_USB && d.inputPortCount > 0 && d.outputPortCount > 0

    private fun tryOpen(d: MidiDeviceInfo) {
        if (!usable(d) || closed) return
        val m = mm ?: return
        info = d
        m.openDevice(d, { dev ->
            if (dev == null || closed) {
                info = null
                return@openDevice
            }
            device = dev
            input = dev.openInputPort(0)
            output = dev.openOutputPort(0)
            val parser = MidiStreamParser(listener, ble = false)
            output?.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    parser.feed(msg, offset, count)
                }
            })
            val props = d.properties
            detail = props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                ?: props.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "USB MIDI"
            queue.clear()
            connected = true
            startSender()
            Log.i(TAG, "connected: $detail")
            listener.onLinkState(this, true, detail)
        }, handler)
    }

    private fun startSender() {
        val t = Thread({
            while (connected && !closed) {
                val batch = drain(8, 200) ?: continue
                val port = input ?: break
                try {
                    val b = encode(batch)
                    port.send(b, 0, b.size)
                } catch (e: Exception) {
                    Log.w(TAG, "send failed", e)
                }
                if (!queue.isEmpty()) Thread.sleep(12)
            }
        }, "usb-midi-out")
        t.isDaemon = true
        t.start()
        sender = t
    }

    private fun disconnect(why: String) {
        if (!connected && device == null) return
        connected = false
        try {
            output?.close(); input?.close(); device?.close()
        } catch (_: Exception) {
        }
        output = null; input = null; device = null; info = null
        detail = ""
        Log.i(TAG, "disconnected: $why")
        listener.onLinkState(this, false, why)
    }

    override fun close() {
        closed = true
        mm?.unregisterDeviceCallback(callback)
        disconnect("closed")
    }

    companion object {
        private const val TAG = "UsbMidiLink"
    }
}
