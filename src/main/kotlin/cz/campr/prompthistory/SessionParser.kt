package cz.campr.prompthistory

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.time.Instant

data class Turn(val time: Instant?, val prompt: String, val answer: String?)

data class Session(val id: String, val title: String?, val turns: List<Turn>)

/**
 * Reads a Claude Code transcript (JSONL) line by line. Incremental: feed() is called
 * only with newly appended lines, the instance keeps state between calls.
 */
class SessionParser(private val sessionId: String) {
    private var title: String? = null
    private val turns = mutableListOf<Turn>()
    private var warned = false

    fun feed(line: String) {
        // Cheap pre-filter: most lines are tool results and attachments.
        if (!line.contains("\"type\":\"user\"") && !line.contains("\"type\":\"assistant\"") &&
            !line.contains("\"custom-title\"") && !line.contains("\"queued_command\"")
        ) return
        // A broken line is skipped; otherwise it would stop reading the whole file.
        try {
            val obj = JsonParser.parseString(line).asJsonObject
            if (obj.bool("isSidechain")) return
            when (obj.str("type")) {
                "custom-title" -> obj.str("customTitle")?.let { title = it }
                "user" -> onUser(obj)
                "assistant" -> onAssistant(obj)
                "attachment" -> onAttachment(obj)
            }
        } catch (e: Exception) {
            warnOnce("unreadable line skipped", e)
        }
    }

    /** One warning per session; after a format change every line would flood the log. */
    private fun warnOnce(what: String, e: Exception) {
        if (warned) return
        warned = true
        Logger.getInstance(SessionParser::class.java).warn("Prompt History: session $sessionId: $what", e)
    }

    fun snapshot() = Session(sessionId, title, turns.toList())

    private fun onUser(obj: JsonObject) {
        if (obj.bool("isMeta") || obj.bool("isCompactSummary")) return
        // Automated sessions run through the SDK (e.g. security review), not user prompts.
        if (obj.str("promptSource") == "sdk" || obj.str("entrypoint")?.startsWith("sdk") == true) return
        val content = obj.obj("message")?.get("content") ?: return
        val raw = when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> {
                val blocks = content.asJsonArray.mapNotNull { it as? JsonObject }
                if (blocks.any { it.str("type") == "tool_result" }) return
                blocks.filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString(" ")
            }
            else -> return
        }
        addTurn(raw, obj.str("timestamp"))
    }

    /** A prompt typed while Claude was working, delivered mid-turn as an attachment. */
    private fun onAttachment(obj: JsonObject) {
        val att = obj.obj("attachment") ?: return
        if (att.str("type") != "queued_command") return
        if (att.obj("origin")?.str("kind") != "human") return
        val raw = att.str("prompt") ?: return
        addTurn(raw, obj.str("timestamp"))
    }

    private fun addTurn(raw: String, timestamp: String?) {
        val prompt = promptText(raw.trim()) ?: return
        val time = timestamp?.let {
            try {
                Instant.parse(it)
            } catch (e: Exception) {
                warnOnce("unparseable timestamp '$it'", e)
                null
            }
        }
        turns += Turn(time, prompt, null)
    }

    private fun onAssistant(obj: JsonObject) {
        if (turns.isEmpty()) return
        // Skip "No response requested." etc. after resuming an interrupted session; keep API errors.
        val message = obj.obj("message") ?: return
        if (message.str("model") == "<synthetic>" && !obj.bool("isApiErrorMessage")) return
        val content = message.get("content")?.takeIf { it.isJsonArray } ?: return
        val text = content.asJsonArray.mapNotNull { it as? JsonObject }
            .lastOrNull { it.str("type") == "text" && !it.str("text").isNullOrBlank() }
            ?.str("text") ?: return
        turns[turns.lastIndex] = turns.last().copy(answer = firstLine(text))
    }

    private fun promptText(raw: String): String? {
        if (raw.isEmpty() || raw.startsWith("[Request interrupted")) return null
        if (raw.startsWith("<command-")) {
            val name = tag(raw, "command-name") ?: return null
            if (name in setOf("/clear", "/exit")) return null
            val args = tag(raw, "command-args").orEmpty()
            return "$name $args".trim()
        }
        if (raw.startsWith("<bash-input>")) return tag(raw, "bash-input")?.let { "! $it" }
        val unwrapped = raw.replace(pastedContent) { it.groupValues[1].trim() }.trim()
        if (unwrapped != raw) return unwrapped.ifEmpty { null }
        // Agent notifications, local command stdout, system reminders etc.
        if (raw.startsWith("<")) return null
        return raw
    }

    private val pastedContent =
        Regex("<pasted_content[^>]*>(.*?)</pasted_content[^>]*>", RegexOption.DOT_MATCHES_ALL)

    private fun tag(s: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1)?.trim()

    private fun firstLine(text: String): String =
        text.lineSequence().map { it.trim().trimStart('#', '*', '-', '>', ' ').trimEnd('*') }
            .firstOrNull { it.isNotEmpty() } ?: text.trim()

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asBoolean == true
}
