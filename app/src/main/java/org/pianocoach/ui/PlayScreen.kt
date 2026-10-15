package org.pianocoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.pianocoach.Coach
import org.pianocoach.Snapshot
import org.pianocoach.ViewMode
import org.pianocoach.core.HandSel
import org.pianocoach.core.Mode
import org.pianocoach.core.PlayState
import org.pianocoach.core.fmtTime
import org.pianocoach.core.keyName
import org.pianocoach.core.stars

@Composable
fun PlayScreen(coach: Coach, onBack: () -> Unit, onSettings: () -> Unit) {
    val prefs = coach.prefs
    val loaded = coach.loaded
    val song = loaded?.song
    val opts = coach.options
    var snap by remember { mutableStateOf(Snapshot.EMPTY) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { snap = coach.snapshot() }
    }
    val compact = LocalConfiguration.current.screenHeightDp < 700

    Column(Modifier.fillMaxSize().background(C.bg)) {
        // ---- top bar: song, the view switch, piano ----
        Row(
            Modifier.fillMaxWidth().height(if (compact) 50.dp else 56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Library", tint = C.text) }
            Column(Modifier.weight(1f)) {
                Text(
                    loaded?.title ?: "No song", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (loaded != null && loaded.artist.isNotEmpty()) {
                    Text(loaded.artist, color = C.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Segmented(
                listOf(ViewMode.Falling, ViewMode.Sheet), prefs.view,
                label = { if (it == ViewMode.Falling) "Falling notes" else "Sheet music" },
                onSelect = { prefs.view = it; prefs.save() },
                height = 42.dp,
                color = { C.pressed },
                icon = { v, on ->
                    Icon(
                        if (v == ViewMode.Falling) Icons.Filled.Piano else Icons.Filled.LibraryMusic, null,
                        tint = if (on) Color.White else C.dim, modifier = Modifier.size(20.dp),
                    )
                },
            )
            PianoChip(coach, onSettings)
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = C.dim) }
        }

        // ---- controls ----
        Row(
            Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Segmented(
                listOf(Mode.Learn, Mode.Follow, Mode.Listen), opts.mode,
                label = { it.name }, onSelect = { coach.setMode(it) },
            )
            Segmented(
                listOf(HandSel.Both, HandSel.Right, HandSel.Left), opts.hands,
                label = { if (it == HandSel.Both) "Both" else if (it == HandSel.Right) "R" else "L" },
                onSelect = { coach.setHands(it) },
                color = { if (it == HandSel.Right) C.right.copy(alpha = 0.8f) else if (it == HandSel.Left) C.left else C.raised },
            )
            Transport(coach, snap)
            Tempo(coach, opts.tempoPct)
            LoopButton(coach, snap)
            Spacer(Modifier.weight(1f))
            StatusText(snap, opts.mode)
        }

        // ---- progress ----
        if (song != null) {
            ProgressBar(snap, song.durationMs, coach.loopA, onSeek = { coach.seek(it) })
        }

        // ---- the music ----
        val range = KeyLayout.forSong(song, prefs.fitRange)
        val kbH = if (compact) 110.dp else 140.dp
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (prefs.view == ViewMode.Falling) {
                FallingNotes(
                    snap, song, opts.mode, opts.hands, range, prefs.rollNames, kbH,
                    onKey = { k, d -> coach.screenKey(k, d) }, modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    SheetView(
                        snap, song, opts.mode, opts.hands, prefs.sheetColors, prefs.noteNames,
                        onSeek = { coach.seek(it) }, modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    if (prefs.sheetKeyboard) {
                        KeyboardOnly(
                            snap, song, opts.mode, opts.hands, range, prefs.rollNames,
                            onKey = { k, d -> coach.screenKey(k, d) },
                            modifier = Modifier.fillMaxWidth().height(if (compact) 80.dp else 100.dp),
                        )
                    }
                }
            }
            if (song == null) {
                Text(
                    "Pick a song in the Library.", color = C.dim, fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    coach.result?.let { r -> ResultDialog(coach, r) }
}

@Composable
fun PianoChip(coach: Coach, onClick: () -> Unit) {
    val name = coach.pianoName
    Chip(if (name != null) "● $name" else "○ No piano", if (name != null) C.ok else C.faint, onClick = onClick)
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, primary: Boolean = false, onClick: () -> Unit) {
    if (primary) {
        FilledIconButton(
            onClick = onClick, modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = C.accent),
        ) { Icon(icon, desc, tint = Color.White) }
    } else {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { Icon(icon, desc, tint = C.text) }
    }
}

@Composable
private fun Transport(coach: Coach, snap: Snapshot) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.Filled.Replay, "Restart") { coach.restart() }
        val playing = snap.state == PlayState.Playing || snap.state == PlayState.Waiting
        RoundIcon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play", primary = true) { coach.togglePlay() }
        RoundIcon(Icons.Filled.Stop, "Stop") { coach.stop() }
    }
}

@Composable
private fun Tempo(coach: Coach, pct: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { coach.setTempo(pct - 10) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, "Slower", tint = C.text)
        }
        Text(
            "$pct%", color = C.text, fontSize = 15.sp, maxLines = 1, softWrap = false,
            textAlign = TextAlign.Center, modifier = Modifier.width(56.dp),
        )
        IconButton(onClick = { coach.setTempo(pct + 10) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, "Faster", tint = C.text)
        }
    }
}

@Composable
private fun LoopButton(coach: Coach, snap: Snapshot) {
    val loop = snap.loop
    when {
        loop != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Chip("A–B  #${snap.loops + 1}", C.warn, onClick = { coach.clearLoop() })
            IconButton(onClick = { coach.clearLoop() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, "Clear loop", tint = C.dim)
            }
        }
        coach.loopA != null -> Chip("Set B", C.warn, onClick = { coach.loopTap() })
        else -> Chip("A", C.dim, onClick = { coach.loopTap() })
    }
}

@Composable
private fun StatusText(snap: Snapshot, mode: Mode) {
    if (mode == Mode.Listen) return
    val s = snap.stats
    val prompt = if (mode == Mode.Learn && snap.state == PlayState.Waiting && snap.waiting.isNotEmpty()) {
        "Play  " + snap.waiting.sorted().joinToString(" ") { keyName(it) }
    } else null
    val score = if (mode == Mode.Learn) {
        "✓ ${s.correct}  ✗ ${s.wrong}  · ${s.stepsDone}/${s.stepsTotal}"
    } else {
        "✓ ${s.hits}  ✗ ${s.misses + s.wrong}  · ${s.accuracy}%"
    }
    val streak = if (s.streak >= 3) "   streak ${s.streak}" else ""
    Column(horizontalAlignment = Alignment.End) {
        // Explicit line heights: the theme's 24 sp lines would not fit two in the row.
        if (prompt != null) {
            Text(prompt, fontSize = 17.sp, lineHeight = 20.sp, maxLines = 1, color = C.led, fontWeight = FontWeight.Bold)
            Text(score + streak, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, color = C.dim)
        } else {
            Text(score, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 1, color = C.text)
            if (streak.isNotEmpty()) Text(streak.trim(), color = C.ok, fontSize = 12.sp, lineHeight = 15.sp)
        }
    }
}

/** Seekable progress with the A–B section shaded. */
@Composable
private fun ProgressBar(snap: Snapshot, duration: Int, loopA: Int?, onSeek: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(fmtTime(snap.pos), color = C.dim, fontSize = 13.sp, modifier = Modifier.width(40.dp))
        Canvas(
            Modifier.weight(1f).height(24.dp)
                .pointerInput(duration) {
                    detectTapGestures { onSeek((it.x / size.width * duration).toInt().coerceIn(0, duration)) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures { c, _ ->
                        onSeek((c.position.x / size.width * duration).toInt().coerceIn(0, duration))
                    }
                },
        ) {
            val y = size.height / 2
            val d = maxOf(1, duration).toFloat()
            drawRoundRect(C.raised, Offset(0f, y - 3f), Size(size.width, 6f), CornerRadius(3f))
            snap.loop?.let { (a, b) ->
                drawRect(C.warn.copy(alpha = 0.35f), Offset(a / d * size.width, y - 7f), Size((b - a) / d * size.width, 14f))
            }
            loopA?.let { drawRect(C.warn, Offset(it / d * size.width - 1f, y - 9f), Size(3f, 18f)) }
            val px = snap.pos / d * size.width
            drawRoundRect(C.accent, Offset(0f, y - 3f), Size(px, 6f), CornerRadius(3f))
            drawCircle(Color.White, 7f, Offset(px, y))
        }
        Text(fmtTime(duration), color = C.dim, fontSize = 13.sp, modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun ResultDialog(coach: Coach, r: org.pianocoach.RunResult) {
    val acc = r.stats.accuracy
    AlertDialog(
        onDismissRequest = { coach.result = null },
        confirmButton = { TextButton(onClick = { coach.result = null; coach.restart() }) { Text("Again") } },
        dismissButton = { TextButton(onClick = { coach.result = null }) { Text("Close") } },
        title = { Text(if (r.newBest) "New best!" else "Well played") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "★".repeat(stars(acc)) + "☆".repeat(3 - stars(acc)),
                    color = C.gold, fontSize = 40.sp,
                )
                Text("Accuracy $acc%", fontSize = 20.sp, color = C.text)
                val s = r.stats
                Text(
                    if (r.mode == Mode.Learn) "${s.correct} right, ${s.wrong} wrong · best streak ${s.bestStreak}"
                    else "${s.hits} hit, ${s.misses} missed, ${s.wrong} wrong · best streak ${s.bestStreak}",
                    color = C.dim,
                )
                if (!r.counted) Text("Play the whole song for the score to count.", color = C.dim, fontSize = 13.sp)
            }
        },
        containerColor = C.surface,
        shape = RoundedCornerShape(16.dp),
    )
}
