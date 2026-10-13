package cz.campr.prompthistory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClaudeProcessesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun <T> withChild(block: (Long) -> T): T {
        val child = ProcessBuilder("sleep", "30").start()
        try {
            return block(child.pid())
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun `session of a registered claude process under the shell is found`() = withChild { childPid ->
        tmp.root.resolve("$childPid.json").writeText("""{"pid":$childPid,"sessionId":"abc","status":"busy"}""")
        val self = ProcessHandle.current().pid()
        assertEquals("abc", ClaudeProcesses(tmp.root).sessionIdUnder(self))
    }

    @Test
    fun `no registered descendant or unknown pid gives null`() = withChild {
        val self = ProcessHandle.current().pid()
        assertNull(ClaudeProcesses(tmp.root).sessionIdUnder(self))
        assertNull(ClaudeProcesses(tmp.root).sessionIdUnder(Long.MAX_VALUE))
    }

    @Test
    fun `broken registration is ignored`() = withChild { childPid ->
        tmp.root.resolve("$childPid.json").writeText("{nevalidni")
        assertNull(ClaudeProcesses(tmp.root).sessionIdUnder(ProcessHandle.current().pid()))
    }
}
