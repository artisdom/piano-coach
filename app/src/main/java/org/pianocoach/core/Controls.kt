package org.pianocoach.core

import kotlin.random.Random

/**
 * Piano-key remote control (port of piano-core `controls.rs`): the lowest
 * and highest keys of the 88 act as buttons, so the app can be driven from
 * the piano alone.
 *
 * A control key works whenever nothing is being played, and during a song
 * only if that song never uses the key — a real note is never taken for a
 * command. Nearly all songs stay inside C1–C7.
 *
 * ```
 * low end (left hand)                    high end (right hand)
 * A0  previous song                      D7  Left hand
 * A#0 tempo −10 %                        E7  Both hands
 * B0  next song                          F7  Right hand
 * C1  play / pause                       G7  Learn
 * C#1 tempo +10 %                        A7  Follow
 * D1  restart the song                   B7  Listen
 * E1  previous folder (random song)      C8  bookmark the song
 * F1  next folder (random song)
 * G1  random song in this folder
 * G#1 shuffle-play this folder (on / off)
 * ```
 */
sealed class Control(val label: String) {
    data object PrevSong : Control("Previous song")
    data object NextSong : Control("Next song")
    data object PlayPause : Control("Play / pause")
    data object Restart : Control("Restart")
    data object PrevFolder : Control("Previous folder")
    data object NextFolder : Control("Next folder")
    data object RandomSong : Control("Random song")
    data object ShuffleFolder : Control("Shuffle folder")
    data object TempoDown : Control("Tempo −10 %")
    data object TempoUp : Control("Tempo +10 %")
    data class SetMode(val mode: Mode) : Control(mode.name)
    data class SetHands(val hands: HandSel) : Control(if (hands == HandSel.Both) "Both hands" else "${hands.name} hand")
    data object Bookmark : Control("Bookmark")
}

/** The key map and the availability rule (kept apart from [Control] so its objects exist first). */
object ControlKeys {
    /** The key map (MIDI key → control), lowest key first. */
    val KEYS: List<Pair<Int, Control>> = listOf(
        21 to Control.PrevSong, // A0
        22 to Control.TempoDown, // A#0
        23 to Control.NextSong, // B0
        24 to Control.PlayPause, // C1
        25 to Control.TempoUp, // C#1
        26 to Control.Restart, // D1
        28 to Control.PrevFolder, // E1
        29 to Control.NextFolder, // F1
        31 to Control.RandomSong, // G1
        32 to Control.ShuffleFolder, // G#1
        98 to Control.SetHands(HandSel.Left), // D7
        100 to Control.SetHands(HandSel.Both), // E7
        101 to Control.SetHands(HandSel.Right), // F7
        103 to Control.SetMode(Mode.Learn), // G7
        105 to Control.SetMode(Mode.Follow), // A7
        107 to Control.SetMode(Mode.Listen), // B7
        108 to Control.Bookmark, // C8
    )

    fun of(key: Int): Control? = KEYS.firstOrNull { it.first == key }?.second

    /** Keys a song plays at all (any hand). */
    fun usedKeys(song: Song): BooleanArray {
        val used = BooleanArray(128)
        for (n in song.notes) used[n.key and 127] = true
        return used
    }

    /**
     * What a press of [key] means: a control if the key is one and either
     * nothing is being played ([busy] false) or the song never uses it.
     */
    fun press(key: Int, busy: Boolean, used: BooleanArray?): Control? {
        val c = of(key) ?: return null
        if (busy && used != null && used[key and 127]) return null
        return c
    }
}

/**
 * Shuffle-play: a folder's songs in random order, each once. [first], if one
 * of them, plays first (the song already playing when shuffle is switched on).
 */
class Shuffle(songs: List<String>, first: String?, seed: Long) {
    private val order: List<String>
    private var pos = 0

    init {
        val o = songs.shuffled(Random(seed)).toMutableList()
        if (first != null && o.remove(first)) o.add(0, first)
        order = o
    }

    val size get() = order.size

    /** The song playing now. */
    val current: String? get() = order.getOrNull(pos)

    /** Songs started so far (the current one included). */
    val played get() = minOf(pos + 1, order.size)

    /** Move [d] songs on (never before the first); null once past the last. */
    fun step(d: Int): String? {
        val p = maxOf(0, pos + d)
        if (p >= order.size) {
            pos = order.size
            return null
        }
        pos = p
        return current
    }
}
