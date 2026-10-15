package org.pianocoach.core

/**
 * Small polyphonic additive piano synthesizer (port of piano-core
 * `synth.rs`): each voice sums inharmonic sine partials with their own
 * exponential decay, plus a short hammer-noise transient; Note Off engages
 * the damper unless the sustain pedal is down.
 *
 * Not thread-safe: the audio thread and the player synchronise on it.
 */
class Synth(private val rate: Float) {
    private class Voice {
        var key = 0
        var active = false
        var released = false
        var sustained = false
        val phase = IntArray(PARTIALS)
        val step = IntArray(PARTIALS)
        val amp = FloatArray(PARTIALS)
        val decay = FloatArray(PARTIALS) { 1f }
        var attack = 0f
        var attackStep = 0f
        var damper = 1f
        var dampCoef = 1f
        var hammerLeft = 0
        var hammerAmp = 0f
        var noiseLp = 0f
        var age = 0L

        fun level() = amp.sum() * damper
        fun reset() {
            active = false; released = false; sustained = false
            phase.fill(0); step.fill(0); amp.fill(0f); decay.fill(1f)
            attack = 0f; attackStep = 0f; damper = 1f; dampCoef = 1f
            hammerLeft = 0; hammerAmp = 0f; noiseLp = 0f
        }
    }

    private val voices = Array(MAX_VOICES) { Voice() }
    private var sustain = false
    private var clock = 0L
    private var noise = 0x12345678

    /** Master gain (0..1). */
    var volume = 0.8f

    fun activeVoices() = voices.count { it.active }

    /** Handle one packed channel message (see [msg]). */
    fun midi(m: Int) {
        val s = (m shr 16) and 0xf0
        val k = (m shr 8) and 0x7f
        val v = m and 0x7f
        when {
            s == 0x90 && v > 0 -> noteOn(k, v)
            s == 0x90 || s == 0x80 -> noteOff(k)
            s == 0xb0 && k == 64 -> setSustain(v >= 64)
            s == 0xb0 && (k == 120 || k == 123) -> allOff()
        }
    }

    fun noteOn(key: Int, vel: Int) {
        clock++
        // Re-strike: reuse the voice already playing this key.
        var idx = voices.indexOfFirst { it.active && it.key == key }
        if (idx < 0) idx = voices.indexOfFirst { !it.active }
        if (idx < 0) {
            // Steal the quietest voice, preferring released ones.
            idx = voices.indices.minWith(compareBy({ !voices[it].released }, { voices[it].level() }))
        }
        val f = 440f * Math.pow(2.0, (key - 69) / 12.0).toFloat()
        val v = vel.coerceIn(1, 127) / 127f
        val loud = Math.pow(v.toDouble(), 1.7).toFloat() * 0.32f
        // Brightness: soft notes lose upper partials faster.
        val bright = 0.35f + 0.5f * v
        // Fundamental decay: ~3 s at middle C, longer in the bass.
        val t1 = (3.0 * Math.pow(261.6 / f, 0.6)).toFloat().coerceIn(0.6f, 7f)
        val nyquist = rate * 0.45f
        val voice = voices[idx]
        voice.reset()
        for (k in 0 until PARTIALS) {
            val n = (k + 1).toFloat()
            val fk = f * n * Math.sqrt((1f + INHARMONICITY * n * n).toDouble()).toFloat()
            if (fk >= nyquist) continue // amp stays 0: no aliasing
            voice.step[k] = (fk / rate * 4_294_967_296.0).toLong().toInt()
            voice.phase[k] = k * 0x5f000000
            voice.amp[k] = loud * Math.pow(bright.toDouble(), k.toDouble()).toFloat() / Math.sqrt(n.toDouble()).toFloat()
            val tk = t1 / (1f + 0.9f * k)
            voice.decay[k] = Math.exp(-1.0 / (tk * rate)).toFloat()
        }
        voice.key = key
        voice.active = true
        voice.attackStep = 1f / (ATTACK_S * rate)
        voice.dampCoef = Math.exp(-1.0 / (DAMPER_S * rate)).toFloat()
        voice.hammerLeft = (HAMMER_S * rate).toInt()
        voice.hammerAmp = loud * 0.25f * v
        voice.age = clock
    }

    fun noteOff(key: Int) {
        for (v in voices) if (v.active && v.key == key && !v.released) {
            v.released = true
            v.sustained = sustain
        }
    }

    fun setSustain(down: Boolean) {
        sustain = down
        if (!down) for (v in voices) v.sustained = false
    }

    fun allOff() {
        for (v in voices) v.reset()
        sustain = false
    }

    private val mix = FloatArray(256)

    /** Mix the next [n] mono samples into [out]. */
    fun render(out: ShortArray, n: Int) {
        var off = 0
        while (off < n) {
            val len = minOf(256, n - off)
            java.util.Arrays.fill(mix, 0, len, 0f)
            for (v in voices) {
                if (!v.active) continue
                val damping = v.released && !v.sustained
                var live = 0
                for (k in PARTIALS - 1 downTo 0) if (v.amp[k] > SILENT) { live = k + 1; break }
                for (i in 0 until len) {
                    var x = 0f
                    for (k in 0 until live) {
                        x += sine(v.phase[k]) * v.amp[k]
                        v.phase[k] += v.step[k]
                        v.amp[k] *= v.decay[k]
                    }
                    if (v.hammerLeft > 0) {
                        noise = noise xor (noise shl 13)
                        noise = noise xor (noise ushr 17)
                        noise = noise xor (noise shl 5)
                        val white = noise.toFloat() / Int.MAX_VALUE
                        v.noiseLp += 0.25f * (white - v.noiseLp)
                        x += v.noiseLp * v.hammerAmp
                        v.hammerLeft--
                    }
                    if (v.attack < 1f) v.attack = minOf(1f, v.attack + v.attackStep)
                    if (damping) v.damper *= v.dampCoef
                    mix[i] += x * v.attack * v.damper
                }
                for (k in 0 until PARTIALS) if (v.amp[k] < SILENT) v.amp[k] = 0f
                if (v.level() < SILENT && v.hammerLeft == 0) v.active = false
            }
            for (i in 0 until len) {
                // Soft clip: gentle saturation instead of hard wrap-around.
                val y = mix[i] * volume
                out[off + i] = (y / (1f + Math.abs(y)) * 30_000f).toInt().toShort()
            }
            off += len
        }
    }

    companion object {
        const val PARTIALS = 6
        const val MAX_VOICES = 20
        private const val SILENT = 1e-4f
        private const val INHARMONICITY = 0.0004f
        private const val DAMPER_S = 0.12f
        private const val ATTACK_S = 0.002f
        private const val HAMMER_S = 0.006f

        /** sin(2π·phase/2³²), max error ≈ 0.001. */
        private fun sine(phase: Int): Float {
            val x = phase * (1f / 2_147_483_648f)
            val y = 4f * x - 4f * x * Math.abs(x)
            return 0.225f * (y * Math.abs(y) - y) + y
        }
    }
}
