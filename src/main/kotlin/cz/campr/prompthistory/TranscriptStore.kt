package cz.campr.prompthistory

import java.io.File
import java.io.RandomAccessFile

/**
 * Transcripts of one project in ~/.claude/projects/<slug>/. refresh() reads only
 * what was appended since the last call.
 */
class TranscriptStore(private val dir: File, private val maxSessions: Int = 30) {

    private class Tracked(val parser: SessionParser) {
        var offset = 0L
        var seenLength = 0L
        var mtime = 0L
    }

    private val tracked = HashMap<String, Tracked>()

    private var lastIds = emptyList<String>()

    /**
     * Returns up to maxSessions most recently active sessions that have at least one
     * prompt, or null when nothing changed since the last call.
     */
    fun refresh(): List<Session>? {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty()
            .map { it to it.lastModified() }
            .sortedByDescending { it.second }
        val result = mutableListOf<Session>()
        val visited = HashSet<String>()
        var changed = false
        for ((f, mod) in files) {
            if (result.size >= maxSessions) break
            visited += f.name
            val len = f.length()
            var t = tracked.getOrPut(f.name) { Tracked(SessionParser(f.name.removeSuffix(".jsonl"))) }
            if (len < t.offset) {
                // The file was rewritten; start over.
                t = Tracked(SessionParser(f.name.removeSuffix(".jsonl")))
                tracked[f.name] = t
            }
            if (mod != t.mtime || len != t.seenLength) {
                t.mtime = mod
                t.seenLength = len
                if (readNew(f, t)) changed = true
            }
            t.parser.snapshot().takeIf { it.turns.isNotEmpty() }?.let(result::add)
        }
        tracked.keys.retainAll(visited)
        val ids = result.map { it.id }
        if (!changed && ids == lastIds) return null
        lastIds = ids
        return result
    }

    /** Returns true when at least one complete line was read. */
    private fun readNew(f: File, t: Tracked): Boolean {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            if (len <= t.offset) return false
            val buf = ByteArray((len - t.offset).toInt())
            raf.seek(t.offset)
            raf.readFully(buf)
            // Leave an unfinished last line for the next round.
            val end = buf.lastIndexOf('\n'.code.toByte())
            if (end < 0) return false
            String(buf, 0, end, Charsets.UTF_8).lineSequence().forEach(t.parser::feed)
            t.offset += end + 1
            return true
        }
    }

    companion object {
        fun forProject(basePath: String): TranscriptStore {
            val slug = basePath.replace(Regex("[^A-Za-z0-9]"), "-")
            return TranscriptStore(File(claudeConfigDir(), "projects/$slug"))
        }
    }
}
