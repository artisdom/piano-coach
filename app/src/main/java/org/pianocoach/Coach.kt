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
import org.pianocoach.core.Control
import org.pianocoach.core.ControlKeys
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
import org.pianocoach.core.Shuffle
import org.pianocoach.core.Song
import org.pianocoach.core.Stats
import org.pianocoach.core.msg
import org.pianocoach.core.noteOff
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

    /** Every launch starts in Follow (with a 儿时回忆 song, see [bootSong]). */
    var options by mutableStateOf(Options(mode = Mode.Follow))
        private set

    /** Bumped when the piano keys start a song: the activity opens Play. */
    var playRequests by mutableIntStateOf(0)
        private set

    /** Shuffle-play (G#1): folder, order, progress for the Play page. */
    private var shuffle: Shuffle? = null
    private var shuffleDir = ""
    var shuffleText by mutableStateOf<String?>(null)
        private set

    /** Keys the loaded song plays (notes, not controls, mid-song). */
    @Volatile
    private var usedKeys: BooleanArray? = null

    /** Control keys held down: their release is not passed on. */
    private val heldControls = BooleanArray(128)

    /** The boot song waits for the piano (or plays at once on the tablet). */
    private var autostart = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
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
        main.post { bootSong() }
    }

    // ================================================================ navigation

    private fun dirOf(path: String) = path.substringBeforeLast('/', "")
    private fun songsIn(dir: String) = list(dir).filter { !it.isDir }.map { it.path }
    private fun subdirs(dir: String) = list(dir).filter { it.isDir }.map { it.path }

    /** The song [d] places from [path] in its folder (wrapping). */
    private fun songStep(path: String, d: Int): String? {
        val songs = songsIn(dirOf(path).let { if (it == rootAlias) "" else it })
        if (songs.isEmpty()) return null
        val i = songs.indexOf(path).coerceAtLeast(0)
        return songs[Math.floorMod(i + d, songs.size)]
    }

    private fun randomSong(dir: String): String? = songsIn(dir).randomOrNull()

    /** First ([d] > 0) or last folder holding songs in [dir]'s subtree, [dir] included (depth-first). */
    private fun edgeFolder(dir: String, d: Int, depth: Int = 0): String? {
        if (depth > 8) return null
        val songs = songsIn(dir)
        if (d > 0 && songs.isNotEmpty()) return dir
        val subs = if (d > 0) subdirs(dir) else subdirs(dir).reversed()
        for (s in subs) edgeFolder(s, d, depth + 1)?.let { return it }
        return if (d < 0 && songs.isNotEmpty()) dir else null
    }

    /** Every song anywhere below [dir]. */
    private fun songsBelow(dir: String): List<String> = songsIn(dir) + subdirs(dir).flatMap { songsBelow(it) }

    /**
     * The next ([d] > 0) or previous top-level category (a folder right below
     * the library root that holds songs) after the one [path] is in, wrapping.
     * E1 / F1: below the categories the folders are often one film or
     * composer with a single song, so stepping those felt like stepping songs.
     */
    private fun categoryStep(path: String, d: Int): String? {
        val cats = subdirs("").filter { songsBelow(it).isNotEmpty() }
        if (cats.isEmpty()) return null
        val i = cats.indexOfFirst { path.startsWith("$it/") }
        return cats[when {
            i >= 0 -> Math.floorMod(i + d, cats.size)
            d > 0 -> 0
            else -> cats.size - 1
        }]
    }

    /** A folder whose display title is [title] (breadth-first, a few levels). */
    private fun findFolder(title: String): String? {
        var level = listOf("")
        repeat(4) {
            val next = ArrayList<String>()
            for (d in level) for (s in subdirs(d)) {
                if (Names.entry(s, true).title == title) return s
                next.add(s)
            }
            level = next
        }
        return null
    }

    /** Every launch: a random 儿时回忆 song in Follow, started once the piano is there. */
    private fun bootSong() {
        if (loaded != null) return
        val dir = findFolder(START_FOLDER) ?: edgeFolder("", 1) ?: return
        val path = randomSong(dir) ?: return
        if (!loadAsset(path)) return
        playRequests++
        autostart = true
        maybeAutostart()
    }

    private fun maybeAutostart() {
        if (!autostart) return
        if (activeLink != null || prefs.sound == SoundOut.Speaker) {
            autostart = false
            start()
        }
    }

    /** Load and start a library song chosen from the piano. */
    private fun playPath(path: String, fromShuffle: Boolean = false) {
        if (!fromShuffle) endShuffle(null)
        if (loadAsset(path, fromShuffle)) {
            start()
            playRequests++
        }
    }

    private fun endShuffle(msg: String?) {
        if (shuffle == null) return
        shuffle = null
        shuffleText = null
        if (msg != null) toast = msg
    }

    private fun showShuffle() {
        val sh = shuffle ?: return
        shuffleText = "Shuffle ${Names.entry(shuffleDir, true).title} ${sh.played} / ${sh.size}"
    }

    /** Shuffle [dir]; [first] plays first if it is one of its songs. */
    private fun startShuffle(dir: String, first: String?): Shuffle? {
        val songs = songsIn(dir)
        if (songs.isEmpty()) return null
        shuffleDir = dir
        return Shuffle(songs, first, System.nanoTime()).also { shuffle = it; showShuffle() }
    }

    // ================================================================ piano keys

    /** A piano-key control (main thread). */
    private fun control(c: Control) {
        val current = loaded?.id?.takeIf { !it.startsWith("file:") }
        val dir = current?.let { dirOf(it) }
        var msg: String? = c.label
        val sh = shuffle
        when (c) {
            Control.PlayPause -> if (loaded != null) togglePlay()
            Control.Restart -> if (loaded != null) restart()
            Control.TempoDown, Control.TempoUp -> {
                setTempo(options.tempoPct + if (c == Control.TempoUp) 10 else -10)
                msg = "Tempo ${options.tempoPct} %"
            }
            is Control.SetMode -> setMode(c.mode)
            is Control.SetHands -> setHands(c.hands)
            Control.Bookmark -> {
                val l = loaded
                if (l == null) msg = null else {
                    toggleBookmark(l.id)
                    msg = if (progress.get(l.id).bookmarked) "Bookmarked ★" else "Bookmark removed"
                }
            }
            Control.ShuffleFolder -> when {
                sh != null -> { endShuffle(null); msg = "Shuffle off" }
                dir == null -> msg = "Pick a library song first"
                else -> {
                    val playing = synchronized(lock) { engine?.state }.let { it == PlayState.Playing || it == PlayState.Waiting }
                    val s = startShuffle(dir, current)
                    if (s == null) msg = "No songs to shuffle here" else {
                        msg = "Shuffle ${Names.entry(dir, true).title}: ${s.size} songs"
                        if (s.current != current || !playing) s.current?.let { playPath(it, fromShuffle = true) }
                    }
                }
            }
            Control.PrevSong, Control.NextSong -> {
                val d = if (c == Control.NextSong) 1 else -1
                if (sh != null) {
                    val next = sh.step(d)
                    if (next == null) endShuffle("Shuffle: folder done") else { showShuffle(); playPath(next, fromShuffle = true) }
                } else current?.let { songStep(it, d) }?.let { playPath(it) }
            }
            Control.PrevCategory, Control.NextCategory -> {
                // A random song from anywhere in the next category; with
                // shuffle on, its folder is shuffled from that song.
                val d = if (c == Control.NextCategory) 1 else -1
                val cat = current?.let { categoryStep(it, d) }
                val song = cat?.let { songsBelow(it).randomOrNull() }
                if (cat != null && song != null) {
                    if (sh != null) {
                        startShuffle(dirOf(song), song)?.current?.let { playPath(it, fromShuffle = true) }
                        msg = shuffleText
                    } else {
                        playPath(song)
                        msg = "${c.label}: ${Names.entry(cat, true).title}"
                    }
                }
            }
            Control.RandomSong -> dir?.let { randomSong(it) }?.let { playPath(it) }
        }
        msg?.let { toast = it }
    }

    /** Shuffle-play: the next song, a moment after one has ended. */
    private fun shuffleNext() {
        val sh = shuffle ?: return
        if (synchronized(lock) { engine?.state } != PlayState.Finished) return
        val next = sh.step(1)
        if (next == null) {
            endShuffle("Shuffle: folder done")
        } else {
            showShuffle()
            toast = shuffleText
            playPath(next, fromShuffle = true)
        }
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

    /** Load a library song; any song not chosen by shuffle-play ends it. */
    fun loadAsset(path: String, fromShuffle: Boolean = false): Boolean {
        if (!fromShuffle) main.post { endShuffle("Shuffle off") }
        val bytes = runCatching {
            getApplication<Application>().assets.open("midi/$path").use { it.readBytes() }
        }.getOrElse { return fail("Cannot open $path") }
        val e = Names.entry(path, false)
        return load(path, e.title, e.subtitle, bytes)
    }

    fun loadUri(uri: Uri): Boolean {
        endShuffle("Shuffle off")
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
            usedKeys = ControlKeys.usedKeys(song)
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
        if (shuffle != null) main.postDelayed({ shuffleNext() }, SHUFFLE_GAP_MS)
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
        // Control keys (core/Controls.kt): any time nothing plays, mid-song
        // only those the song never uses.
        if (down) {
            val busy = synchronized(lock) { engine?.state }.let { it == PlayState.Playing || it == PlayState.Waiting }
            val c = ControlKeys.press(key, busy, usedKeys)
            if (c != null) {
                heldControls[key and 127] = true
                flashKey(key)
                main.post { control(c) }
                return
            }
        } else if (heldControls[key and 127]) {
            heldControls[key and 127] = false
            return
        }
        keyInput(key, down)
    }

    /** Light a control key's LED for a moment (unless the song lights it meanwhile). */
    private fun flashKey(key: Int) {
        activeLink?.send(noteOn(key, 1))
        main.postDelayed({
            val lit = synchronized(lock) { engine?.led(key) == true }
            if (!lit) activeLink?.send(noteOff(key))
        }, 600)
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
                maybeAutostart()
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

        /** The folder every launch starts in. */
        const val START_FOLDER = "儿时回忆"

        /** Pause after a song ends before the next shuffled one starts. */
        private const val SHUFFLE_GAP_MS = 2500L
    }
}
