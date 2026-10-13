package cz.campr.prompthistory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun session(name: String, mtime: Long, prompt: String?) {
        val f = tmp.root.resolve("$name.jsonl")
        f.writeText(
            if (prompt == null) "{\"type\":\"custom-title\",\"customTitle\":\"t\"}\n"
            else "{\"type\":\"user\",\"message\":{\"content\":\"$prompt\"}}\n"
        )
        f.setLastModified(mtime)
    }

    @Test
    fun `limit counts only sessions with prompts`() {
        session("new-empty", 3000, null)
        session("mid", 2000, "b")
        session("old", 1000, "a")
        val ids = TranscriptStore(tmp.root, maxSessions = 2).refresh()!!.map { it.id }
        assertEquals(listOf("mid", "old"), ids)
    }

    @Test
    fun `unchanged files return null, appended line is read`() {
        session("s", 1000, "a")
        val store = TranscriptStore(tmp.root)
        store.refresh()
        assertNull(store.refresh())
        tmp.root.resolve("s.jsonl").appendText("{\"type\":\"user\",\"message\":{\"content\":\"b\"}}\n")
        assertEquals(listOf("a", "b"), store.refresh()!!.single().turns.map { it.prompt })
    }

    @Test
    fun `line with non-object message neither stops reading nor duplicates turns`() {
        session("s", 1000, "a")
        val f = tmp.root.resolve("s.jsonl")
        f.appendText("{\"type\":\"user\",\"message\":null}\n{\"type\":\"attachment\",\"attachment\":\"queued_command\"}\n")
        val store = TranscriptStore(tmp.root)
        assertEquals(listOf("a"), store.refresh()!!.single().turns.map { it.prompt })
        f.appendText("{\"type\":\"user\",\"message\":{\"content\":\"b\"}}\n")
        assertEquals(listOf("a", "b"), store.refresh()!!.single().turns.map { it.prompt })
    }

    @Test
    // Czech diacritics on purpose: the split must not break multi-byte UTF-8 characters.
    fun `unfinished last line waits for its newline`() {
        session("s", 1000, "a")
        val f = tmp.root.resolve("s.jsonl")
        f.appendText("{\"type\":\"user\",\"message\":{\"content\":\"příliš")
        val store = TranscriptStore(tmp.root)
        assertEquals(listOf("a"), store.refresh()!!.single().turns.map { it.prompt })
        f.appendText(" žluťoučký\"}}\n")
        assertEquals(listOf("a", "příliš žluťoučký"), store.refresh()!!.single().turns.map { it.prompt })
    }
}
