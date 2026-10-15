package org.pianocoach.core

/**
 * Per-song bookmarks, best scores and play history (port of piano-core
 * `progress.rs`), stored as the same tab-separated text the firmware keeps
 * on its SD card:
 *
 *     # piano-coach progress v1: path, bookmarked, best learn %, best follow %, plays, last played seq
 *     儿时回忆（41）/卖报歌 - 聂耳.mid	1	92	-	5	17
 */
data class Record(
    val bookmarked: Boolean = false,
    val bestLearn: Int? = null,
    val bestFollow: Int? = null,
    val plays: Int = 0,
    /** Ordering key for "recently played" (higher = more recent). */
    val last: Long = 0,
)

/** 0–3 stars for an accuracy percentage. */
fun stars(acc: Int) = when {
    acc >= 95 -> 3
    acc >= 80 -> 2
    acc >= 60 -> 1
    else -> 0
}

fun starText(acc: Int?): String =
    if (acc == null) "—" else "★".repeat(stars(acc)) + "☆".repeat(3 - stars(acc)) + " $acc%"

class Progress {
    private val songs = java.util.TreeMap<String, Record>()
    private var seq = 0L

    fun toText(): String = buildString {
        append(HEADER).append('\n')
        fun pct(v: Int?) = v?.toString() ?: "-"
        for ((p, r) in songs) {
            append("$p\t${if (r.bookmarked) 1 else 0}\t${pct(r.bestLearn)}\t${pct(r.bestFollow)}\t${r.plays}\t${r.last}\n")
        }
    }

    operator fun get(path: String): Record = songs[path] ?: Record()

    private fun update(path: String, f: (Record) -> Record) {
        val key = path.replace(Regex("[\t\n\r]"), " ")
        val r = f(songs[key] ?: Record())
        if (r == Record()) songs.remove(key) else songs[key] = r
    }

    fun toggleBookmark(path: String): Boolean {
        update(path) { it.copy(bookmarked = !it.bookmarked) }
        return get(path).bookmarked
    }

    fun recordPlay(path: String) {
        seq++
        update(path) { it.copy(plays = it.plays + 1, last = seq) }
    }

    /** Store a finished run's accuracy; true if it is a new best. */
    fun recordScore(path: String, learn: Boolean, acc: Int): Boolean {
        val a = acc.coerceIn(0, 100)
        val r = get(path)
        val old = if (learn) r.bestLearn else r.bestFollow
        if (old != null && a <= old) return false
        update(path) { if (learn) it.copy(bestLearn = a) else it.copy(bestFollow = a) }
        return true
    }

    fun bookmarks(): List<String> = songs.filter { it.value.bookmarked }.keys.toList()

    fun recent(n: Int): List<String> =
        songs.filter { it.value.last > 0 }.entries.sortedByDescending { it.value.last }.take(n).map { it.key }

    companion object {
        const val HEADER =
            "# piano-coach progress v1: path, bookmarked, best learn %, best follow %, plays, last played seq"

        fun parse(text: String): Progress {
            val p = Progress()
            for (line in text.lines()) {
                if (line.startsWith("#") || line.isBlank()) continue
                val f = line.split('\t')
                if (f.size < 6) continue
                fun pct(s: String) = s.toIntOrNull()?.takeIf { it in 0..100 }
                val r = Record(f[1] == "1", pct(f[2]), pct(f[3]), f[4].toIntOrNull() ?: 0, f[5].toLongOrNull() ?: 0)
                p.seq = maxOf(p.seq, r.last)
                p.songs[f[0]] = r
            }
            return p
        }
    }
}
