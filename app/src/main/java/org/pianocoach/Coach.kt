package org.pianocoach

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.pianocoach.audio.SpeakerOut
import org.pianocoach.core.Engine
import org.pianocoach.core.Entry
import org.pianocoach.core.HandSel
import org.pianocoach.core.Mode
import org.pianocoach.core.MsgList
import org.pianocoach.core.Names
import org.pianocoach.core.Options
import org.pianocoach.core.PlayState
import org.pianocoach.core.Progress
import org.pianocoach.core.SearchIndex
import org.pianocoach.core.Song
import org.pianocoach.core.Stats
import org.pianocoach.core.msg
import org.pianocoach.core.noteOn
import org.pianocoach.midi.BleMidiLink
import org.pianocoach.midi.PianoLink
import org.pianocoach.midi.PianoListener
import org.pianocoach.midi.UsbMidiLink
import java.io.File
import kotlin.concurrent.thread

/** A song that has been loaded. [id] keys progress: library path or "file:<name>". */
class Loaded(val id: String, val title: String, val artist: String, val song: Song)

/** Finished Learn/Follow run, for the result card. */
class RunResult(val mode: Mode, val stats: Stats, val newBest: Boolean, val counted: Boolean)

/** What the screen draws each frame (copied under the engine lock). */
class Snapshot(
    val pos: Int,
    val state: PlayState,
    val stats: Stats,
    val hint: BooleanArray,
    val sounding: BooleanArray,
    val pressed: BooleanArray,
    val stepMs: Int,
    val stepKeys: List<Int>,
    val waiting: List<Int>,
    val loop: Pair<Int, Int>?,
    val loops: Int,
    val wrongKey: Int,
    val wrongAt: Long,
) {
    companion object {
        val EMPTY = Snapshot(
            0, PlayState.Stopped, Stats(), BooleanArray(128), BooleanArray(128), BooleanArray(128),
            -1, emptyList(), emptyList(), null, 0, -1, 0,
        )
    }
}

/**
 * The app: library, piano connection, player thread and settings. One
 * instance lives as long as the activity's ViewModel store.
 */
class Coach(app: Application) : AndroidViewModel(app), PianoListener {
    val prefs = Prefs(app)

    // ---- library ----
    /** Folder path ("" = root) → entries, folders first. */
    private val tree = HashMap<String, List<Entry>>()

    /** The single top-level folder shown as the root, if any. */
    @Volatile
    private var rootAlias = ""
    var libraryReady by mutableStateOf(false)
        private set
    var search: SearchIndex? = null
        private set
    private val progressFile = File(app.filesDir, "progress.tsv")
    val progress: Progress = runCatching { Progress.parse(progressFile.readText()) }.getOrElse { Progress() }

    /** Bumped when progress changes (Compose re-reads). */
    var progressVersion by mutableIntStateOf(0)
        private set

    // ---- player ----
    private val lock = Object()
    private var engine: Engine? = null
    var loaded by mutableStateOf<Loaded?>(null)
        private set
    var options by mutableStateOf(Options())
        private set
    var result by mutableStateOf<RunResult?>(null)
    var toast by mutableStateOf<String?>(null)
    private val out = MsgList()
    private var wrongKey = -1
    private var wrongAt = 0L
    private var lastState = PlayState.Stopped

    /** Loop A point set, waiting for B. */
    var loopA by mutableStateOf<Int?>(null)
        private set

    // ---- piano ----
    val speaker = SpeakerOut()
    private val usb = UsbMidiLink(app, this)
    private val ble = BleMidiLink(app, this)
    var usbState by mutableStateOf("not connected")
        private set
    var bleState by mutableStateOf("off")
        private set
    var pianoName by mutableStateOf<String?>(null)
        private set
    var panel by mutableStateOf("")
        private set

    @Volatile
    private var running = true

    init {
        speaker.volume = prefs.volume
        thread(name = "library") { buildLibrary() }
        usb.start()
        thread(name = "player") { playerLoop() }
    }

    // ================================================================ library

    private fun buildLibrary() {
        val t0 = SystemClock.uptimeMillis()
        val am = getApplication<Application>().assets
        val pinyin = HashMap<Char, String>(4096)
        am.open("pinyin.tsv").bufferedReader().forEachLine { l ->
            if (l.length > 2) pinyin[l[0]] = l.substring(2)
        }
        val idx = SearchIndex(pinyin)
        fun walk(dir: String) {
            val names = am.list(if (dir.isEmpty()) "midi" else "midi/$dir") ?: return
            val entries = names.filter { !it.startsWith(".") }.map { n ->
                val p = if (dir.isEmpty()) n else "$dir/$n"
                Names.entry(p, !Names.isMidi(n))
            }.sortedWith(compareBy<Entry> { !it.isDir }.thenBy(Names.naturalOrder) { it.path.substringAfterLast('/') })
            tree[dir] = entries
            val folder = if (dir.isEmpty()) "" else Names.entry(dir, true).title
            for (e in entries) if (e.isDir) walk(e.path) else idx.add(e, folder)
        }
        walk("")
        // A single top-level folder (the JustPiano pack): start inside it.
        val root = tree[""].orEmpty()
        if (root.size == 1 && root[0].isDir) {
            rootAlias = root[0].path
            tree[""] = tree[root[0].path].orEmpty()
        }
        search = idx
        Log.i(TAG, "library: ${idx.size} songs in ${SystemClock.uptimeMillis() - t0} ms")
        libraryReady = true
    }

    fun list(dir: String): List<Entry> = tree[dir].orEmpty()

    fun parentOf(dir: String): String = dir.substringBeforeLast('/', "").let { if (it == rootAlias) "" else it }

    fun entryFor(path: String): Entry = Names.entry(path, false)

    private fun saveProgress() {
        progressVersion++
        val text = progress.toText()
        thread { runCatching { progressFile.writeText(text) } }
    }

    fun toggleBookmark(id: String) {
        progress.toggleBookmark(id)
        saveProgress()
    }

    // ================================================================ loading

    fun loadAsset(path: String): Boolean {
        val bytes = runCatching {
            getApplication<Application>().assets.open("midi/$path").use { it.readBytes() }
        }.getOrElse { return fail("Cannot open $path") }
        val e = Names.entry(path, false)
        return load(path, e.title, e.subtitle, bytes)
    }

    fun loadUri(uri: Uri): Boolean {
        val cr = getApplication<Application>().contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "song.mid"
        val bytes = runCatching { cr.openInputStream(uri)!!.use { it.readBytes() } }
            .getOrElse { return fail("Cannot open $name") }
        val e = Names.entry(name, false)
        return load("file:$name", e.title, e.subtitle, bytes)
    }

    private fun fail(m: String): Boolean {
        toast = m
        return false
    }

    private fun load(id: String, title: String, artist: String, bytes: ByteArray): Boolean {
        val song = runCatching { Song.parse(bytes) }.getOrElse { return fail("$title: ${it.message}") }
        if (song.notes.isEmpty()) return fail("$title has no notes")
        synchronized(lock) {
            engine?.stop(out)
            engine = Engine(song, options.copy(tempoPct = 100))
            options = engine!!.options
            lastState = PlayState.Stopped
            loopA = null
        }
        flush()
        loaded = Loaded(id, title, artist, song)
        result = null
        return true
    }

    // ================================================================ transport

    private fun engineOpts(o: Options) = o.copy(
        accompany = prefs.accompany,
        easy = prefs.easy,
        ledOffOnHit = prefs.ledOffOnHit,
        leadMs = prefs.leadMs,
    )

    fun setMode(m: Mode) = changeOptions(options.copy(mode = m))
    fun setHands(h: HandSel) = changeOptions(options.copy(hands = h))

    /** Settings that feed the engine changed. */
    fun settingsChanged() {
        prefs.save()
        speaker.volume = prefs.volume
        changeOptions(options)
    }

    private fun changeOptions(o: Options) {
        synchronized(lock) {
            val e = engine
            val n = engineOpts(o)
            if (e != null) {
                e.setOptions(n, out)
                lastState = e.state
            }
            options = n
        }
        flush()
    }

    fun setTempo(pct: Int) {
        synchronized(lock) {
            engine?.setTempo(pct)
            options = options.copy(tempoPct = pct.coerceIn(25, 200))
        }
    }

    fun start(mode: Mode? = null) {
        if (mode != null && mode != options.mode) setMode(mode)
        synchronized(lock) {
            val e = engine ?: return
            if (e.state == PlayState.Stopped || e.state == PlayState.Finished) {
                loaded?.let { progress.recordPlay(it.id) }
                result = null
            }
            e.play(SystemClock.uptimeMillis(), out)
            lastState = e.state
        }
        saveProgress()
        flush()
    }

    fun togglePlay() {
        val st = synchronized(lock) { engine?.state } ?: return
        if (st == PlayState.Playing || st == PlayState.Waiting) pause() else start()
    }

    fun pause() {
        synchronized(lock) {
            engine?.pause(out)
            engine?.let { lastState = it.state }
        }
        flush()
    }

    fun stop() {
        synchronized(lock) {
            engine?.stop(out)
            engine?.let { lastState = it.state }
        }
        flush()
    }

    fun restart() {
        synchronized(lock) {
            val e = engine ?: return
            e.stop(out)
            e.play(SystemClock.uptimeMillis(), out)
            lastState = e.state
            result = null
        }
        flush()
    }

    fun seek(ms: Int) {
        synchronized(lock) { engine?.seek(ms, out) }
        flush()
    }

    /** A–B loop: first tap sets A, second sets B, third clears. */
    fun loopTap() {
        synchronized(lock) {
            val e = engine ?: return
            val a = loopA
            when {
                e.loopRange != null -> {
                    e.setLoop(null, out); loopA = null
                }
                a == null -> loopA = e.song.onsetAtOrBefore(e.positionMs)
                else -> {
                    if (!e.setLoop(a to e.positionMs, out)) toast = "Loop too short"
                    loopA = null
                }
            }
        }
        flush()
    }

    fun clearLoop() {
        synchronized(lock) {
            engine?.setLoop(null, out)
            loopA = null
        }
        flush()
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val e = engine ?: return Snapshot.EMPTY
        val hint = BooleanArray(128) { e.isHint(it) }
        val snd = BooleanArray(128) { e.isSounding(it) }
        val pr = BooleanArray(128) { e.isPressed(it) }
        val st = e.currentStep()
        Snapshot(
            e.positionMs, e.state, e.stats, hint, snd, pr,
            st?.ms ?: -1, st?.keys?.toList() ?: emptyList(), e.waitingKeys(),
            e.loopRange, e.loops, wrongKey, wrongAt,
        )
    }

    private fun playerLoop() {
        while (running) {
            var active: Boolean
            synchronized(lock) {
                val e = engine
                active = e != null && (e.state == PlayState.Playing || e.state == PlayState.Waiting)
                if (e != null && active) {
                    e.tick(SystemClock.uptimeMillis(), out)
                    if (e.state == PlayState.Finished && lastState != PlayState.Finished) finished(e)
                    lastState = e.state
                }
            }
            flush()
            Thread.sleep(if (active) 2 else 15)
        }
    }

    private fun finished(e: Engine) {
        val l = loaded ?: return
        val o = e.options
        if (o.mode == Mode.Listen) return
        val s = e.stats
        // Only a run that covered most of the song counts.
        val covered = if (o.mode == Mode.Learn) s.stepsDone else s.hits + s.misses
        val counted = s.stepsTotal > 0 && covered * 10 >= s.stepsTotal * 9
        val best = counted && progress.recordScore(l.id, o.mode == Mode.Learn, s.accuracy)
        if (counted) saveProgress()
        result = RunResult(o.mode, s, best, counted)
    }

    // ================================================================ keys

    override fun onKey(key: Int, down: Boolean, vel: Int) {
        keyInput(key, down)
    }

    /** On-screen keyboard: also sounds on the tablet. */
    fun screenKey(key: Int, down: Boolean) {
        if (down) speaker.noteOn(key, 90) else speaker.noteOff(key)
        keyInput(key, down)
    }

    private fun keyInput(key: Int, down: Boolean) {
        synchronized(lock) {
            val e = engine ?: return
            if (down) {
                val w = e.stats.wrong
                e.keyDown(key, out)
                if (e.stats.wrong > w) {
                    wrongKey = key
                    wrongAt = SystemClock.uptimeMillis()
                }
            } else {
                e.keyUp(key)
            }
        }
        flush()
    }

    override fun onPanel(param: Int, value: Int) {
        panel = when (param) {
            0x2A -> "Volume $value"
            0x2B -> "Metronome $value"
            0x15 -> "Tone $value"
            else -> "Param 0x%02X = %d".format(param, value)
        }
    }

    // ================================================================ output

    private val activeLink: PianoLink?
        get() = if (usb.connected) usb else if (ble.connected) ble else null

    /** Route the engine's messages: hints to the piano, sound per settings. */
    private fun flush() {
        val n = synchronized(lock) {
            if (out.size == 0) return
            val copy = out.data.copyOf(out.size)
            out.clear()
            copy
        }
        val link = activeLink
        val sound = prefs.sound
        val toPiano = link != null && sound != SoundOut.Speaker
        val toSpeaker = sound == SoundOut.Speaker || sound == SoundOut.Both || (sound == SoundOut.Auto && link == null)
        val hintVel = options.hintVel
        for (m in n) {
            val s = (m shr 16) and 0xf0
            val key = (m shr 8) and 0x7f
            val vel = m and 0x7f
            if (s == 0x90 && vel in 1..hintVel) {
                link?.send(m) // silent hint LED
            } else if (s == 0x90 && vel > 0) {
                // Speaker only: the piano still shows the note's LED, silently.
                link?.send(if (toPiano) m else noteOn(key, 1))
                if (toSpeaker) speaker.midi(m)
            } else {
                link?.send(m)
                speaker.midi(m)
            }
        }
    }

    /** Settings test buttons. */
    fun lightAll(on: Boolean) {
        val link = activeLink ?: return run { toast = "No piano connected" }
        if (on) for (k in 21..108) link.send(noteOn(k, 1)) else link.send(msg(0xB0, 123, 0))
    }

    // ================================================================ connection

    fun setBluetooth(on: Boolean) {
        prefs.bluetooth = on
        prefs.save()
        if (on) startBluetooth() else ble.close()
    }

    /** Called once permissions are granted. */
    fun startBluetooth() {
        if (prefs.bluetooth && ble.available && !usb.connected) ble.start()
        bleState = ble.status
    }

    override fun onLinkState(link: PianoLink, connected: Boolean, detail: String) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            if (link === usb) {
                usbState = if (connected) "connected: $detail" else "not connected"
                // USB wins; Bluetooth keeps the piano's only BLE slot otherwise.
                if (connected) ble.close() else startBluetooth()
            } else {
                bleState = detail
            }
            pianoName = activeLink?.let { "${it.kind}: ${it.detail}" }
            if (connected) {
                // Re-light the current hints on the new link.
                changeOptions(options)
            }
        }
    }

    override fun onCleared() {
        running = false
        synchronized(lock) { engine?.stop(out) }
        flush()
        usb.close()
        ble.close()
        speaker.release()
    }

    companion object {
        private const val TAG = "Coach"
    }
}
