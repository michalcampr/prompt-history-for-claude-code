package cz.campr.prompthistory

import com.google.gson.JsonParser
import java.io.File
import kotlin.jvm.optionals.getOrNull

/**
 * A running Claude Code writes ~/.claude/sessions/<pid>.json with its current sessionId
 * (it changes after /clear). This finds the session of the process under a given shell.
 */
class ClaudeProcesses(private val sessionsDir: File) {

    fun sessionIdUnder(shellPid: Long): String? {
        val shell = ProcessHandle.of(shellPid).getOrNull() ?: return null
        return shell.descendants().iterator().asSequence()
            .map { File(sessionsDir, "${it.pid()}.json") }
            .filter(File::isFile)
            .firstNotNullOfOrNull(::readSessionId)
    }

    private fun readSessionId(f: File): String? = try {
        JsonParser.parseString(f.readText()).asJsonObject.get("sessionId")?.asString
    } catch (_: Exception) {
        null
    }

    companion object {
        fun default() = ClaudeProcesses(File(claudeConfigDir(), "sessions"))
    }
}

internal fun claudeConfigDir(): File =
    File(System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.claude"))
