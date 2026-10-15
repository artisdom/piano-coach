package org.pianocoach.core

/**
 * Naming, ordering and search of library entries (port of piano-core
 * `library.rs` and `search.rs`). The bundled JustPiano library names
 * folders `分类（41）` and songs `标题 - 作者.mid`.
 */
class Entry(
    /** Path relative to the library root ("folder/song.mid"). */
    val path: String,
    val isDir: Boolean,
    val title: String,
    /** Folder: "41 songs"; song: the composer/artist ("" if none). */
    val subtitle: String,
)

object Names {
    fun isMidi(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".mid") || n.endsWith(".midi") || n.endsWith(".kar") || n.endsWith(".rmi")
    }

    /** Split "name（41）" / "name(41)" into ("name", 41). */
    private fun splitCount(name: String): Pair<String, Int?> {
        val t = name.trimEnd()
        for ((open, close) in listOf('（' to '）', '(' to ')')) {
            if (t.endsWith(close)) {
                val inner = t.dropLast(1)
                val i = inner.lastIndexOf(open)
                if (i >= 0) inner.substring(i + 1).trim().toIntOrNull()?.let {
                    return inner.substring(0, i).trimEnd() to it
                }
            }
        }
        return t to null
    }

    fun entry(path: String, isDir: Boolean): Entry {
        val name = path.substringAfterLast('/')
        return if (isDir) {
            var (title, count) = splitCount(name)
            // Drop the JustPiano "小项A：" prefix.
            val i = title.indexOf('：')
            if (title.startsWith("小项") && i >= 0) title = title.substring(i + 1)
            Entry(path, true, title, count?.let { "$it songs" } ?: "")
        } else {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val i = stem.lastIndexOf(" - ")
            if (i >= 0) Entry(path, false, stem.substring(0, i).trim(), stem.substring(i + 3).trim())
            else Entry(path, false, stem.trim(), "")
        }
    }

    /** Compare with digit runs as numbers ("a2" < "a10"). */
    val naturalOrder = Comparator<String> { a, b -> naturalCmp(a, b) }

    private fun naturalCmp(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (true) {
            if (i >= a.length && j >= b.length) return 0
            if (i >= a.length) return -1
            if (j >= b.length) return 1
            val c = a[i]
            val d = b[j]
            if (c.isDigit() && d.isDigit()) {
                val si = i
                while (i < a.length && a[i].isDigit()) i++
                val sj = j
                while (j < b.length && b[j].isDigit()) j++
                val t1 = a.substring(si, i).trimStart('0')
                val t2 = b.substring(sj, j).trimStart('0')
                val o = if (t1.length != t2.length) t1.length.compareTo(t2.length) else t1.compareTo(t2)
                if (o != 0) return o
            } else {
                val o = c.lowercaseChar().compareTo(d.lowercaseChar())
                if (o != 0) return o
                i++
                j++
            }
        }
    }
}

/** Library-wide search by Latin text, full pinyin or pinyin initials. */
class SearchIndex(private val pinyin: Map<Char, String>) {
    class SongRef(val entry: Entry, val folder: String, val keys: Array<String>)

    private val songs = ArrayList<SongRef>()

    /** (full, initials) search keys for a display string. */
    fun keys(s: String): Pair<String, String> {
        val full = StringBuilder()
        val init = StringBuilder()
        var inWord = false
        for (c in s) {
            val py = pinyin[c]
            if (c.code < 128 && c.isLetterOrDigit()) {
                val l = c.lowercaseChar()
                full.append(l)
                if (!inWord) init.append(l)
                inWord = true
            } else if (py != null) {
                full.append(py)
                init.append(py[0])
                inWord = false
            } else {
                inWord = false
            }
        }
        return full.toString() to init.toString()
    }

    fun add(e: Entry, folder: String) {
        val (tf, ti) = keys(e.title)
        val (af, ai) = keys(e.subtitle)
        songs.add(SongRef(e, folder, arrayOf(tf, ti, af, ai)))
    }

    val size get() = songs.size

    /** Best matches first (title prefix, title contains, artist). */
    fun search(query: String, limit: Int = 200): List<SongRef> {
        val q = query.filter { it.code < 128 && it.isLetterOrDigit() }.lowercase()
        if (q.isEmpty()) return emptyList()
        return songs.mapNotNull { s ->
            val (tf, ti, af, ai) = s.keys
            val rank = when {
                ti.startsWith(q) || tf.startsWith(q) -> 0
                ti.contains(q) || tf.contains(q) -> 1
                ai.startsWith(q) || af.contains(q) -> 2
                else -> return@mapNotNull null
            }
            Triple(rank, s.entry.title.length, s)
        }.sortedWith(compareBy({ it.first }, { it.second })).take(limit).map { it.third }
    }

    private operator fun <T> Array<T>.component4() = this[3]
}
