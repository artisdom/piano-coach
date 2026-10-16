package org.pianocoach.core

/**
 * Play / learn engine as a pure state machine (port of piano-core
 * `engine.rs`). Fed the monotonic clock ([tick]), keyboard input and UI
 * commands; it returns the MIDI messages to send to the piano.
 *
 * Piano facts this relies on (GZUT_piano_protocol.md):
 * * Note On lights a key's LED; velocity 1 lights it without sound.
 * * Note Off (or CC 123) turns LEDs off; host LEDs ignore physical keys.
 * * Everything is sent on MIDI channel 1.
 *
 * Messages are 3-byte channel messages packed into an Int: status << 16 |
 * data1 << 8 | data2.
 */
fun msg(s: Int, d1: Int, d2: Int) = (s shl 16) or (d1 shl 8) or d2
fun noteOn(key: Int, vel: Int) = msg(0x90, key, vel)
fun noteOff(key: Int) = msg(0x80, key, 0)
val ALL_NOTES_OFF = msg(0xB0, 123, 0)
val SUSTAIN_OFF = msg(0xB0, 64, 0)

/** Growable list of packed messages (no boxing on the hot path). */
class MsgList {
    var data = IntArray(64)
    var size = 0
    fun add(m: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = m
    }
    fun clear() { size = 0 }
    operator fun get(i: Int) = data[i]
}

/** Lowest velocity the piano makes audible is between 8 and 16. */
private const val MIN_AUDIBLE_VEL = 20

/**
 * Follow mode: a tracked hand counts as played by the learner from their
 * first hit on one of its notes until this long (playing time) without one;
 * meanwhile its notes sound softly as a guide, otherwise at full velocity.
 */
const val FOLLOW_IDLE_MS = 4000L

/** Follow guide: the learner's notes at this share of their velocity. */
private const val GUIDE_VEL_PCT = 40
private const val GUIDE_VEL_MIN = 24
private const val GUIDE_VEL_MAX = 48

/** Follow mode: a press this early/late still counts as a hit. */
private const val HIT_EARLY_MS = 250
private const val HIT_LATE_MS = 300

/** Shortest loop accepted (song ms). */
const val MIN_LOOP_MS = 500

enum class Mode { Listen, Learn, Follow }

/**
 * Listen: the piano plays the song. Learn: LEDs show the next chord and the
 * song waits for it. Follow: plays like Listen with the tracked hands' keys
 * lit ahead; a hand the learner is playing (hits within the last
 * [FOLLOW_IDLE_MS]) sounds softly as a guide and is scored.
 */
data class Options(
    val mode: Mode = Mode.Listen,
    /**
     * Learn: the hand(s) the learner plays. Follow: the hands tracked (lit
     * ahead, played softly while the learner plays them, scored). Listen:
     * hands played.
     */
    val hands: HandSel = HandSel.Both,
    /** 25..200, percent of the file's tempo. */
    val tempoPct: Int = 100,
    /** Learn: the piano plays the other hand (Follow always does). */
    val accompany: Boolean = true,
    /** Learn: any key advances (young children). */
    val easy: Boolean = false,
    /** Learn: turn a hint LED off as soon as its key is pressed. */
    val ledOffOnHit: Boolean = true,
    /** Follow: how long before a note its LED comes on (song ms). */
    val leadMs: Int = 700,
    /** Velocity used for silent LED hints. */
    val hintVel: Int = 1,
)

enum class PlayState { Stopped, Playing, Waiting, Paused, Finished }

data class Stats(
    val stepsDone: Int = 0,
    val stepsTotal: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val hits: Int = 0,
    val misses: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
) {
    /** 0..100 accuracy for the summary card. */
    val accuracy: Int
        get() {
            val good: Int
            val total: Int
            if (hits + misses > 0) {
                good = hits; total = hits + misses + wrong
            } else {
                good = correct; total = correct + wrong
            }
            return if (total == 0) 100 else good * 100 / total
        }
}

private const val T_ON = 0
private const val T_OFF = 1
private const val T_PEDAL = 2
private const val T_HINT_ON = 3
private const val T_HINT_OFF = 4

/** Follow: sound / release target [Timed.a] (softly while its hand is played). */
private const val T_GUIDE_ON = 5
private const val T_GUIDE_OFF = 6

/** A timed output; [a], [b] are key/vel, cc/val or target index. */
private class Timed(val ms: Int, val kind: Int, val a: Int, val b: Int = 0) {
    val isRelease get() = kind == T_OFF || kind == T_HINT_OFF || kind == T_GUIDE_OFF
}

/** Follow target; [guided] = its note is sounding. */
private class Target(val ms: Int, val key: Int, val vel: Int, val hand: Hand, var hit: Boolean, var guided: Boolean = false)

class Engine(val song: Song, opts: Options) {
    var options: Options = opts
        private set
    var state: PlayState = PlayState.Stopped
        private set

    /** Song position in ms (tempo-scaled song time). */
    private var pos = 0.0
    private var lastNow: Long? = null

    /** Wall-clock ms spent playing (Follow's idle timeout ignores pauses). */
    private var playedMs = 0L

    /** Follow: [playedMs] of the last hit per hand (right, left), -1 = none. */
    private val lastHit = longArrayOf(-1, -1)
    private var timeline: List<Timed> = emptyList()
    private var cursor = 0
    private var steps: List<Step> = emptyList()
    private var step = 0

    /** Keys of the current step already played. */
    private val stepHit = ArrayList<Int>()
    private var targets: List<Target> = emptyList()

    /** Ref-counts of audible notes and hint LEDs we turned on. */
    private val sounding = IntArray(128)
    private val hints = IntArray(128)
    private val pressed = BooleanArray(128)

    /** UI flash for a wrong key: key and song ms. */
    var lastWrongKey = -1
        private set
    var lastWrongMs = 0
        private set
    var stats = Stats()
        private set

    /** Practice section [start, end) in song ms. */
    var loopRange: Pair<Int, Int>? = null
        private set

    /** Completed passes through the loop. */
    var loops = 0
        private set

    init {
        rebuild()
    }

    private fun rebuild() {
        val o = options
        val tl = ArrayList<Timed>(song.notes.size * 2 + song.pedals.size)
        val tg = ArrayList<Target>()
        for (n in song.notes) {
            val audible = when (o.mode) {
                Mode.Listen -> o.hands.includes(n.hand)
                Mode.Learn -> o.accompany && !o.hands.includes(n.hand)
                // Tracked hands sound through T_GUIDE_ON instead.
                Mode.Follow -> !o.hands.includes(n.hand)
            }
            if (audible) {
                tl.add(Timed(n.startMs, T_ON, n.key, maxOf(n.vel, MIN_AUDIBLE_VEL)))
                tl.add(Timed(n.endMs, T_OFF, n.key))
            }
            if (o.mode == Mode.Follow && o.hands.includes(n.hand)) {
                val idx = tg.size
                tg.add(Target(n.startMs, n.key, n.vel, n.hand, false))
                tl.add(Timed(maxOf(0, n.startMs - o.leadMs), T_HINT_ON, idx))
                tl.add(Timed(n.startMs + HIT_LATE_MS, T_HINT_OFF, idx))
                // After the hint, so a note at the very start lights first.
                tl.add(Timed(n.startMs, T_GUIDE_ON, idx))
                tl.add(Timed(n.endMs, T_GUIDE_OFF, idx))
            }
        }
        // The pedal belongs to whoever is playing the sound.
        if (o.mode != Mode.Learn || o.accompany) {
            for (p in song.pedals) tl.add(Timed(p.ms, T_PEDAL, p.cc, p.v))
        }
        // Stable sort: at equal times releases precede pedals precede starts.
        tl.sortWith(compareBy<Timed>({ it.ms }, {
            when (it.kind) {
                T_OFF, T_HINT_OFF, T_GUIDE_OFF -> 0
                T_PEDAL -> 1
                else -> 2
            }
        }))
        timeline = tl
        targets = tg
        steps = if (o.mode == Mode.Learn) song.steps(o.hands) else emptyList()
        stats = Stats(stepsTotal = if (o.mode == Mode.Learn) steps.size else targets.size)
    }

    val positionMs: Int get() = pos.toInt()

    /** Keys whose LED we are holding on (hints or sounding notes). */
    fun led(key: Int) = hints[key and 127] > 0 || sounding[key and 127] > 0
    fun isHint(key: Int) = hints[key and 127] > 0
    fun isSounding(key: Int) = sounding[key and 127] > 0
    fun isPressed(key: Int) = pressed[key and 127]

    /** Learn mode: the chord being waited for (keys not yet played). */
    fun waitingKeys(): List<Int> = steps.getOrNull(step)?.keys?.filter { it !in stepHit } ?: emptyList()
    fun currentStep(): Step? = steps.getOrNull(step)

    /** Change options; restarts from the current position. */
    fun setOptions(opts: Options, out: MsgList) {
        val p = pos.toInt()
        val was = state
        silence(out)
        options = opts
        rebuild()
        seekInner(p, out)
        state = when (was) {
            PlayState.Finished -> PlayState.Stopped
            PlayState.Waiting -> PlayState.Playing
            else -> was
        }
    }

    /** Set or clear the practice loop; a range shorter than [MIN_LOOP_MS] is rejected. */
    fun setLoop(range: Pair<Int, Int>?, out: MsgList): Boolean {
        if (range == null) {
            loopRange = null
            loops = 0
            return true
        }
        val a = range.first
        val b = minOf(range.second, song.durationMs)
        if (b < a + MIN_LOOP_MS) return false
        loopRange = a to b
        loops = 0
        val p = pos.toInt()
        if (p < a || p >= b) {
            silence(out)
            seekInner(a, out)
            if (state == PlayState.Waiting || state == PlayState.Finished) state = PlayState.Playing
        }
        return true
    }

    /** Only tempo changed: no restart needed. */
    fun setTempo(pct: Int) {
        options = options.copy(tempoPct = pct.coerceIn(25, 200))
    }

    fun play(now: Long, out: MsgList) {
        if (state == PlayState.Stopped || state == PlayState.Finished) {
            silence(out)
            rebuild()
            loops = 0
            lastHit.fill(-1)
            seekInner(loopRange?.first ?: 0, out)
        }
        state = PlayState.Playing
        lastNow = now
        tick(now, out)
    }

    fun pause(out: MsgList) {
        if (state == PlayState.Playing || state == PlayState.Waiting) {
            // Release sounding notes; hint LEDs stay.
            releaseSounding(out)
            state = PlayState.Paused
            lastNow = null
        }
    }

    fun stop(out: MsgList) {
        silence(out)
        state = PlayState.Stopped
        pos = 0.0
        cursor = 0
        step = 0
        lastNow = null
    }

    /** Jump to [ms] (song time). Keeps playing if it was playing. */
    fun seek(ms: Int, out: MsgList) {
        silence(out)
        seekInner(ms.coerceIn(0, song.durationMs), out)
        if (state == PlayState.Finished || state == PlayState.Waiting) state = PlayState.Playing
    }

    private fun seekInner(ms: Int, out: MsgList) {
        pos = ms.toDouble()
        cursor = partition(timeline.size) { timeline[it].ms < ms }
        step = partition(steps.size) { steps[it].ms < ms }
        stepHit.clear()
        for (t in targets) {
            t.hit = t.ms < ms
            t.guided = false
        }
        if (options.mode == Mode.Learn) lightStep(out)
    }

    private fun silence(out: MsgList) {
        val any = sounding.any { it > 0 } || hints.any { it > 0 }
        sounding.fill(0)
        hints.fill(0)
        if (any || state != PlayState.Stopped) {
            out.add(SUSTAIN_OFF)
            out.add(ALL_NOTES_OFF)
        }
    }

    private fun releaseSounding(out: MsgList) {
        for (t in targets) t.guided = false
        for (k in 0 until 128) {
            if (sounding[k] > 0) {
                sounding[k] = 0
                if (hints[k] == 0) out.add(noteOff(k))
            }
        }
        out.add(SUSTAIN_OFF)
    }

    private fun soundOn(key: Int, vel: Int, out: MsgList) {
        // Re-strike: the piano retriggers on a second Note On.
        if (sounding[key] > 0) out.add(noteOff(key))
        sounding[key]++
        out.add(noteOn(key, vel))
    }

    private fun soundOff(key: Int, out: MsgList) {
        if (sounding[key] == 0) return
        sounding[key]--
        if (sounding[key] == 0) {
            out.add(noteOff(key))
            // A hint on the same key was cleared by that Note Off: restore.
            if (hints[key] > 0) out.add(noteOn(key, options.hintVel))
        }
    }

    private fun hintOn(key: Int, out: MsgList) {
        if (hints[key] == 0 && sounding[key] == 0) out.add(noteOn(key, options.hintVel))
        hints[key]++
    }

    private fun hintOff(key: Int, out: MsgList) {
        if (hints[key] == 0) return
        hints[key]--
        if (hints[key] == 0 && sounding[key] == 0) out.add(noteOff(key))
    }

    /** Learn mode: light the LEDs of the current step. */
    private fun lightStep(out: MsgList) {
        val s = steps.getOrNull(step) ?: return
        // Not past the practice loop: that chord is never played.
        val lr = loopRange
        if (lr != null && s.ms >= lr.second) return
        for (k in s.keys.toList()) hintOn(k, out)
    }

    private fun finishStep(out: MsgList) {
        steps.getOrNull(step)?.let { s ->
            for (k in s.keys.toList()) {
                if (!(options.ledOffOnHit && k in stepHit)) hintOff(k, out)
            }
        }
        stats = stats.copy(stepsDone = stats.stepsDone + 1)
        step++
        stepHit.clear()
        lightStep(out)
        if (state == PlayState.Waiting) state = PlayState.Playing
    }

    /** Advance the clock and emit everything due. */
    fun tick(now: Long, out: MsgList) {
        if (state != PlayState.Playing && state != PlayState.Waiting) {
            lastNow = null
            return
        }
        val dt = lastNow?.let { maxOf(0L, now - it) } ?: 0L
        lastNow = now
        playedMs += dt
        if (state == PlayState.Playing) pos += dt * options.tempoPct / 100.0
        // Practice loop: checked before the Learn halt, so a chord at or
        // past the end is never waited for.
        loopRange?.let { (a, b) ->
            if (pos >= b) {
                silence(out)
                seekInner(a, out)
                loops++
                state = PlayState.Playing
                return
            }
        }
        // Learn: halt at the next unplayed chord.
        if (options.mode == Mode.Learn) {
            steps.getOrNull(step)?.let { s ->
                if (pos >= s.ms) {
                    pos = s.ms.toDouble()
                    state = PlayState.Waiting
                }
            }
        }
        val p = pos.toInt()
        while (cursor < timeline.size) {
            val t = timeline[cursor]
            // While waiting, release notes ending exactly at the chord but do
            // not start anything at or past it.
            if (t.ms > p || (state == PlayState.Waiting && t.ms == p && !t.isRelease)) break
            cursor++
            when (t.kind) {
                T_ON -> soundOn(t.a, t.b, out)
                T_OFF -> soundOff(t.a, out)
                T_PEDAL -> out.add(msg(0xB0, t.a, t.b))
                T_HINT_ON -> {
                    val tg = targets[t.a]
                    if (!tg.hit) hintOn(tg.key, out)
                }
                T_HINT_OFF -> {
                    val tg = targets[t.a]
                    if (!tg.hit) {
                        hintOff(tg.key, out)
                        // Only a hand the learner is playing can miss.
                        if (handActive(tg.hand)) stats = stats.copy(misses = stats.misses + 1, streak = 0)
                    }
                }
                T_GUIDE_ON -> {
                    val tg = targets[t.a]
                    // Already played (early): the learner's own note sounds.
                    if (!tg.hit) {
                        val vel = if (handActive(tg.hand)) {
                            (tg.vel * GUIDE_VEL_PCT / 100).coerceIn(GUIDE_VEL_MIN, GUIDE_VEL_MAX)
                        } else {
                            maxOf(tg.vel, MIN_AUDIBLE_VEL)
                        }
                        tg.guided = true
                        soundOn(tg.key, vel, out)
                    }
                }
                T_GUIDE_OFF -> {
                    val tg = targets[t.a]
                    if (tg.guided) {
                        tg.guided = false
                        soundOff(tg.key, out)
                    }
                }
            }
        }
        if (cursor >= timeline.size && step >= steps.size && p >= song.durationMs) {
            silence(out)
            state = PlayState.Finished
        }
    }

    fun keyDown(key: Int, out: MsgList) {
        pressed[key and 127] = true
        if (state != PlayState.Playing && state != PlayState.Waiting) return
        when (options.mode) {
            Mode.Listen -> {}
            Mode.Learn -> learnPress(key, out)
            Mode.Follow -> followPress(key, out)
        }
    }

    fun keyUp(key: Int) {
        pressed[key and 127] = false
    }

    private fun learnPress(key: Int, out: MsgList) {
        val s = steps.getOrNull(step) ?: return
        val wanted = key in s.keys
        if (options.easy) {
            stats = stats.copy(correct = stats.correct + 1)
            bumpStreak()
            finishStep(out)
            return
        }
        if (wanted && key !in stepHit) {
            stepHit.add(key)
            stats = stats.copy(correct = stats.correct + 1)
            bumpStreak()
            if (options.ledOffOnHit) hintOff(key, out)
            if (stepHit.size >= s.keys.size) finishStep(out)
        } else if (!wanted) {
            wrong(key)
        }
    }

    private fun followPress(key: Int, out: MsgList) {
        val p = pos.toInt()
        val lo = p - HIT_LATE_MS
        val hi = p + HIT_EARLY_MS
        var i = partition(targets.size) { targets[it].ms < lo }
        var found = -1
        while (i < targets.size && targets[i].ms <= hi) {
            if (targets[i].key == key && !targets[i].hit) {
                found = i
                break
            }
            i++
        }
        if (found >= 0) {
            val t = targets[found]
            t.hit = true
            lastHit[t.hand.ordinal] = playedMs
            stats = stats.copy(hits = stats.hits + 1)
            bumpStreak()
            // Its LED may already be lit (inside the lead window).
            if (t.ms - options.leadMs <= p) hintOff(key, out)
        } else if (handActive(Hand.Right) || handActive(Hand.Left)) {
            // Keys played while just listening are not scored.
            wrong(key)
        }
    }

    /** Follow: the learner is playing this hand (a hit within [FOLLOW_IDLE_MS] of playing time). */
    private fun handActive(h: Hand): Boolean {
        val t = lastHit[h.ordinal]
        return t >= 0 && playedMs - t < FOLLOW_IDLE_MS
    }

    /** Follow: whether the learner is playing the right / left hand now. */
    fun following(): Pair<Boolean, Boolean> = handActive(Hand.Right) to handActive(Hand.Left)

    private fun wrong(key: Int) {
        stats = stats.copy(wrong = stats.wrong + 1, streak = 0)
        lastWrongKey = key
        lastWrongMs = pos.toInt()
    }

    private fun bumpStreak() {
        val s = stats.streak + 1
        stats = stats.copy(streak = s, bestStreak = maxOf(stats.bestStreak, s))
    }
}
