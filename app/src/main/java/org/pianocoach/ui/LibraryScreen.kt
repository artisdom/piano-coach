package org.pianocoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.pianocoach.Coach
import org.pianocoach.core.Entry
import org.pianocoach.core.Mode
import org.pianocoach.core.SplitRule
import org.pianocoach.core.fmtTime
import org.pianocoach.core.starText
import org.pianocoach.core.stars

private enum class Tab { Folders, Bookmarks, Recent }

@Composable
fun LibraryScreen(coach: Coach, onPlay: () -> Unit, onSettings: () -> Unit, onOpenFile: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Folders) }
    var dir by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    @Suppress("UNUSED_VARIABLE") val pv = coach.progressVersion // re-read progress when it changes

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Piano Coach", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onOpenFile,
                colors = ButtonDefaults.buttonColors(containerColor = C.raised, contentColor = C.text),
            ) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open MIDI file")
            }
            PianoChip(coach, onSettings)
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = C.dim) }
        }
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---- list ----
            Column(
                Modifier.weight(1.35f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(C.surface).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Segmented(
                        Tab.entries, tab, label = {
                            when (it) {
                                Tab.Folders -> "Folders"
                                Tab.Bookmarks -> "★"
                                Tab.Recent -> "Recent"
                            }
                        }, onSelect = { tab = it; query = "" },
                        icon = { t, on ->
                            if (t == Tab.Recent) Icon(Icons.Filled.History, null, tint = if (on) Color.White else C.dim, modifier = Modifier.size(18.dp))
                            if (t == Tab.Folders) Icon(Icons.Filled.Folder, null, tint = if (on) Color.White else C.dim, modifier = Modifier.size(18.dp))
                        },
                    )
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("Search: title, pinyin or initials") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.border),
                    )
                }
                val rows: List<Row2> = when {
                    !coach.libraryReady -> emptyList()
                    query.isNotBlank() -> coach.search?.search(query)?.map { Row2(it.entry, it.folder) } ?: emptyList()
                    tab == Tab.Folders -> coach.list(dir).map { Row2(it, null) }
                    tab == Tab.Bookmarks -> coach.progress.bookmarks().filter { !it.startsWith("file:") }.map { Row2(coach.entryFor(it), null) }
                    else -> coach.progress.recent(100).filter { !it.startsWith("file:") }.map { Row2(coach.entryFor(it), null) }
                }
                if (tab == Tab.Folders && query.isBlank() && dir.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            dir = coach.parentOf(dir)
                        }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.dim)
                        Spacer(Modifier.width(8.dp))
                        Text(org.pianocoach.core.Names.entry(dir, true).title, color = C.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (!coach.libraryReady) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (rows.isEmpty()) {
                    Text(
                        when {
                            query.isNotBlank() -> "No songs match “$query”."
                            tab == Tab.Bookmarks -> "No bookmarks yet — tap ☆ on a song."
                            tab == Tab.Recent -> "Nothing played yet."
                            else -> "Empty folder."
                        },
                        color = C.dim, modifier = Modifier.padding(12.dp),
                    )
                } else {
                    val state = rememberLazyListState()
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                        items(rows, key = { it.e.path }) { r ->
                            EntryRow(coach, r) {
                                if (r.e.isDir) dir = r.e.path else coach.loadAsset(r.e.path)
                            }
                        }
                    }
                }
            }
            // ---- song card ----
            SongCard(coach, Modifier.weight(1f).fillMaxHeight(), onPlay)
        }
    }
}

private class Row2(val e: Entry, val folder: String?)

@Composable
private fun EntryRow(coach: Coach, r: Row2, onClick: () -> Unit) {
    val selected = coach.loaded?.id == r.e.path
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) C.raised else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (r.e.isDir) Icons.Filled.Folder else Icons.Filled.MusicNote, null,
            tint = if (r.e.isDir) C.warn else C.right, modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(r.e.title, color = C.text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(r.e.subtitle.ifEmpty { null }, r.folder?.ifEmpty { null }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, color = C.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!r.e.isDir) {
            val rec = coach.progress[r.e.path]
            val best = listOfNotNull(rec.bestLearn, rec.bestFollow).maxOrNull()
            if (best != null) Text("★".repeat(stars(best)).ifEmpty { "·" }, color = C.gold, fontSize = 14.sp)
            if (rec.bookmarked) Icon(Icons.Filled.Star, null, tint = C.gold, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SongCard(coach: Coach, modifier: Modifier, onPlay: () -> Unit) {
    val l = coach.loaded
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(C.surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (l == null) {
            Text("Pick a song", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Browse the folders or search, tap a song, then choose Learn, Follow or Listen.\n\n" +
                    "Learn — the piano lights the next chord and waits for you.\n" +
                    "Follow — keys light ahead of time; the song keeps going.\n" +
                    "Listen — the piano (or the tablet) plays the song.",
                color = C.dim, fontSize = 15.sp,
            )
            return@Column
        }
        val rec = coach.progress[l.id]
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(l.title, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (l.artist.isNotEmpty()) Text(l.artist, color = C.dim, fontSize = 15.sp)
            }
            IconButton(onClick = { coach.toggleBookmark(l.id) }) {
                Icon(if (rec.bookmarked) Icons.Filled.Star else Icons.Filled.StarBorder, "Bookmark", tint = C.gold)
            }
        }
        val s = l.song
        Info("Length", fmtTime(s.durationMs) + "   ·   ${Math.round(s.bpm)} bpm")
        Info(
            "Notes", "${s.rightCount} right hand, ${s.leftCount} left hand" +
                when (s.split) {
                    SplitRule.TrackNames -> ""
                    SplitRule.TrackPitch -> ""
                    SplitRule.MiddleC -> " (split at middle C)"
                },
        )
        Info("Best", "Learn ${starText(rec.bestLearn)}    Follow ${starText(rec.bestFollow)}")
        if (rec.plays > 0) Info("Played", "${rec.plays}×")
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((m, c) in listOf(Mode.Learn to C.led, Mode.Follow to C.pressed, Mode.Listen to C.right.copy(alpha = 0.85f))) {
                Button(
                    onClick = {
                        coach.start(m)
                        onPlay()
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(m.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun Info(k: String, v: String) {
    Row {
        Text(k, color = C.faint, fontSize = 15.sp, modifier = Modifier.width(70.dp))
        Text(v, color = C.text, fontSize = 15.sp)
    }
}
