package org.pianocoach.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import org.pianocoach.core.Synth

/**
 * Plays [Synth] through the tablet's speaker on its own thread. The track
 * runs only while it has been asked to (started lazily on the first note).
 */
class SpeakerOut {
    val rate = 32_000
    val synth = Synth(rate.toFloat())
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun midi(m: Int) {
        synchronized(synth) { synth.midi(m) }
        ensureRunning()
    }

    fun noteOn(key: Int, vel: Int) {
        synchronized(synth) { synth.noteOn(key, vel) }
        ensureRunning()
    }

    fun noteOff(key: Int) = synchronized(synth) { synth.noteOff(key) }

    fun allOff() = synchronized(synth) { synth.allOff() }

    var volume: Float
        get() = synth.volume
        set(v) {
            synth.volume = v
        }

    @Synchronized
    private fun ensureRunning() {
        if (running) return
        running = true
        val t = Thread({ loop() }, "speaker")
        t.start()
        thread = t
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frames = 512
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, frames * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply { if (android.os.Build.VERSION.SDK_INT >= 26) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
            .build()
        val buf = ShortArray(frames)
        track.play()
        var idle = 0
        while (running) {
            val active = synchronized(synth) {
                synth.render(buf, frames)
                synth.activeVoices()
            }
            track.write(buf, 0, frames)
            // Stop the track after ~3 s of silence; the next note restarts it.
            idle = if (active == 0) idle + frames else 0
            if (idle > rate * 3) break
        }
        track.stop()
        track.release()
        synchronized(this) { running = false }
    }

    fun release() {
        running = false
    }
}
