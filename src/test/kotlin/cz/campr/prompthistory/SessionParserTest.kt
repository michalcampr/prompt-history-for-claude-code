package cz.campr.prompthistory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionParserTest {

    private fun user(content: String, extra: String = "") =
        """{"type":"user",$extra"timestamp":"2026-10-13T11:00:00Z","message":{"role":"user","content":$content}}"""

    private fun assistantText(text: String, extra: String = "") =
        """{"type":"assistant",$extra"message":{"content":[{"type":"text","text":${q(text)}}]}}"""

    private fun q(s: String) = com.google.gson.JsonPrimitive(s).toString()

    private fun parse(vararg lines: String) = SessionParser("s1").apply { lines.forEach(::feed) }.snapshot()

    @Test
    fun `prompt gets the last text of the following assistant messages`() {
        val s = parse(
            user(q("fix this")),
            assistantText("Let me look."),
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash"}]}}""",
            user("""[{"type":"tool_result","content":"ok"}]"""),
            assistantText("Fixed, tests are green.\n\nDetails below."),
        )
        assertEquals(1, s.turns.size)
        assertEquals("fix this", s.turns[0].prompt)
        assertEquals("Fixed, tests are green.", s.turns[0].answer)
    }

    @Test
    fun `slash command is shown as name and args`() {
        val s = parse(user(q("<command-message>inc</command-message>\n<command-name>/inc</command-name>\n<command-args>plan:TODO.md:12</command-args>")))
        assertEquals("/inc plan:TODO.md:12", s.turns.single().prompt)
    }

    @Test
    fun `bash input is shown with exclamation mark`() {
        val s = parse(user(q("<bash-input>git status</bash-input>")))
        assertEquals("! git status", s.turns.single().prompt)
    }

    @Test
    fun `noise is skipped`() {
        val s = parse(
            user(q("<local-command-caveat>caveat</local-command-caveat>"), """"isMeta":true,"""),
            user(q("<command-name>/clear</command-name><command-message>clear</command-message><command-args></command-args>")),
            user(q("<command-name>/exit</command-name><command-message>exit</command-message><command-args></command-args>")),
            user(q("<task-notification><summary>done</summary></task-notification>")),
            user(q("<local-command-stdout>out</local-command-stdout>")),
            user("""[{"type":"text","text":"[Request interrupted by user]"}]"""),
            user(q("sidechain"), """"isSidechain":true,"""),
            user(q("summary"), """"isCompactSummary":true,"""),
        )
        assertEquals(emptyList<Turn>(), s.turns)
    }

    @Test
    fun `sidechain assistant text does not overwrite the answer`() {
        val s = parse(
            user(q("ask")),
            assistantText("Main answer"),
            assistantText("Subagent answer", """"isSidechain":true,"""),
        )
        assertEquals("Main answer", s.turns.single().answer)
    }

    @Test
    fun `last custom title wins and answer markdown prefix is stripped`() {
        val s = parse(
            """{"type":"custom-title","customTitle":"first"}""",
            user(q("a")),
            assistantText("## Result\nnothing"),
            """{"type":"custom-title","customTitle":"second"}""",
        )
        assertEquals("second", s.title)
        assertEquals("Result", s.turns.single().answer)
    }

    @Test
    fun `prompt queued while Claude works is a turn, queued task notification is not`() {
        val s = parse(
            user(q("do A")),
            assistantText("Doing A"),
            """{"type":"attachment","timestamp":"2026-10-13T11:05:00Z","attachment":{"type":"queued_command","prompt":"then review-verify","origin":{"kind":"human"},"humanTurn":true}}""",
            """{"type":"attachment","attachment":{"type":"queued_command","prompt":"<task-notification>x</task-notification>","origin":{"kind":"task-notification"}}}""",
            assistantText("Review-verify done"),
        )
        assertEquals(listOf("do A", "then review-verify"), s.turns.map { it.prompt })
        assertEquals(listOf("Doing A", "Review-verify done"), s.turns.map { it.answer })
        assertEquals(java.time.Instant.parse("2026-10-13T11:05:00Z"), s.turns[1].time)
    }

    @Test
    fun `pasted content prompt is shown unwrapped`() {
        val s = parse(
            user(q("<pasted_content id=\"1938\">\n/plan-lite ticket 123\nspec\n</pasted_content id=\"1938\">")),
            user(q("Analysis:\n<pasted_content id=\"830e\">\ntext\n</pasted_content id=\"830e\">")),
        )
        assertEquals("/plan-lite ticket 123\nspec", s.turns[0].prompt)
        assertEquals("Analysis:\ntext", s.turns[1].prompt)
    }

    @Test
    fun `sdk sourced prompts are skipped`() {
        val s = parse(user(q("Review this change for security vulnerabilities."), """"promptSource":"sdk","entrypoint":"sdk-py","""))
        assertEquals(emptyList<Turn>(), s.turns)
    }

    @Test
    fun `synthetic assistant message does not overwrite the answer, api error does`() {
        val s = parse(
            user(q("a")),
            assistantText("Real answer"),
            """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"No response requested."}]}}""",
        )
        assertEquals("Real answer", s.turns.single().answer)
        val e = parse(
            user(q("a")),
            """{"type":"assistant","isApiErrorMessage":true,"message":{"model":"<synthetic>","content":[{"type":"text","text":"Login expired"}]}}""",
        )
        assertEquals("Login expired", e.turns.single().answer)
    }

    @Test
    fun `inline markdown in the answer preview is stripped, code and arithmetic stay`() {
        fun answer(text: String) = parse(user(q("a")), assistantText(text)).turns.single().answer
        assertEquals("Verdict: MERGE. Fixes for MINOR", answer("**Verdict: MERGE.** Fixes for __MINOR__"))
        assertEquals("See the docs for `gh auth`", answer("See [the docs](https://x.y/z) for `gh auth`"))
        assertEquals("2 * 3 = 6", answer("2 * 3 = 6"))
    }

    @Test
    fun `text blocks of array content are joined, broken lines are ignored`() {
        val s = parse(
            "{nevalidni json",
            user("""[{"type":"text","text":"image:"},{"type":"image"},{"type":"text","text":"what is it"}]"""),
        )
        assertEquals("image: what is it", s.turns.single().prompt)
        assertNull(s.turns.single().answer)
    }
}
