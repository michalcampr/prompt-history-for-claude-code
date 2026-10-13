package cz.campr.prompthistory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class HistoryPanelTest {
    private fun turn(p: String, a: String?, min: Long) = Turn(Instant.ofEpochSecond(min * 60), p, a)

    private fun path(panel: HistoryPanel, vararg idx: Int): TreePath {
        var node = panel.tree.model.root as DefaultMutableTreeNode
        val nodes = mutableListOf(node)
        for (i in idx) {
            node = node.getChildAt(i) as DefaultMutableTreeNode
            nodes += node
        }
        return TreePath(nodes.toTypedArray())
    }

    @Test
    fun `refresh keeps selection and manually collapsed turns, expands new turns`() {
        val panel = HistoryPanel(null, poll = false)
        panel.render(listOf(Session("s", "t", listOf(turn("p1", "a1", 1), turn("p2", "a2", 2)))))
        assertTrue(panel.tree.isExpanded(path(panel, 0, 0)))

        panel.tree.collapsePath(path(panel, 0, 0))
        panel.tree.selectionPath = path(panel, 0, 1)

        panel.render(listOf(Session("s", "t", listOf(turn("p1", "a1", 1), turn("p2", "a2", 2), turn("p3", "a3", 3)))))

        assertEquals("p2", ((panel.tree.selectionPath!!.lastPathComponent as DefaultMutableTreeNode).userObject as Turn).prompt)
        assertFalse(panel.tree.isExpanded(path(panel, 0, 0)))
        assertTrue(panel.tree.isExpanded(path(panel, 0, 2)))
    }

    @Test
    fun `re-expanded session shows all its turns with answers`() {
        val panel = HistoryPanel(null, poll = false)
        panel.render(listOf(Session("s", "t", listOf(turn("p1", "a1", 1), turn("p2", "a2", 2)))))
        panel.tree.collapsePath(path(panel, 0, 0))
        panel.tree.collapsePath(path(panel, 0))
        panel.tree.expandPath(path(panel, 0))
        assertTrue(panel.tree.isExpanded(path(panel, 0, 0)))
        assertTrue(panel.tree.isExpanded(path(panel, 0, 1)))
    }

    @Test
    fun `sessions appearing after the list was emptied are visible`() {
        val panel = HistoryPanel(null, poll = false)
        panel.render(listOf(Session("a", null, listOf(turn("x", null, 1)))))
        panel.render(emptyList())
        panel.render(listOf(Session("b", null, listOf(turn("y", null, 2)))))
        assertTrue(panel.tree.isVisible(path(panel, 0)))
    }

    @Test
    fun `sessions are ordered by start, newest first, and dropped sessions disappear`() {
        val panel = HistoryPanel(null, poll = false)
        val old = Session("old", null, listOf(turn("a", null, 1)))
        val new = Session("new", null, listOf(turn("b", null, 5)))
        panel.render(listOf(old, new))
        panel.render(listOf(Session("old", null, listOf(turn("a", null, 1), turn("c", null, 9))), new))
        val root = panel.tree.model.root as DefaultMutableTreeNode
        assertEquals(listOf("new", "old"), (0 until root.childCount).map {
            ((root.getChildAt(it) as DefaultMutableTreeNode).userObject as Session).id
        })
        panel.render(listOf(new))
        assertEquals(1, root.childCount)
    }
}

class RendererTest {
    @Test
    fun `session row does not inherit tooltip of previously rendered prompt`() {
        val r = Renderer()
        val tree = javax.swing.JTree()
        r.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(Turn(null, "full prompt", null)), false, false, true, 0, false)
        val s = Session("s", "t", listOf(Turn(null, "p", null)))
        r.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(s), false, false, false, 1, false)
        org.junit.Assert.assertNull(r.toolTipText)
    }
}

class HistoryPanelFocusTest {
    private fun s(id: String, min: Long) = Session(id, id, listOf(Turn(Instant.ofEpochSecond(min * 60), "p-$id", null)))

    private fun shown(panel: HistoryPanel): List<String> {
        val root = panel.tree.model.root as DefaultMutableTreeNode
        return (0 until root.childCount).map { ((root.getChildAt(it) as DefaultMutableTreeNode).userObject as Session).id }
    }

    // The store returns sessions by last activity, most recent first.
    private val sessions = listOf(s("active", 1), s("other", 5))

    @Test
    fun `focus mode shows only the session of the active terminal, expanded`() {
        val panel = HistoryPanel(null, poll = false)
        panel.update(sessions, focusId = "other")
        assertEquals(listOf("other"), shown(panel))
        assertTrue(panel.tree.isExpanded(TreePath(arrayOf<Any>(panel.tree.model.root, (panel.tree.model.root as DefaultMutableTreeNode).getChildAt(0)))))
    }

    @Test
    fun `without known focus the most recently active session is shown`() {
        val panel = HistoryPanel(null, poll = false)
        panel.update(sessions, focusId = null)
        assertEquals(listOf("active"), shown(panel))
    }

    @Test
    fun `focused session without prompts yet shows nothing`() {
        val panel = HistoryPanel(null, poll = false)
        panel.update(sessions, focusId = "fresh")
        assertEquals(emptyList<String>(), shown(panel))
    }

    @Test
    fun `all sessions toggle shows every session and back`() {
        val panel = HistoryPanel(null, poll = false)
        panel.update(sessions, focusId = "other")
        panel.showAll = true
        assertEquals(listOf("other", "active"), shown(panel))
        panel.showAll = false
        assertEquals(listOf("other"), shown(panel))
    }
}

class CommandHighlightTest {
    private fun fragments(prompt: String): List<Pair<String, Boolean>> {
        val r = Renderer()
        r.getTreeCellRendererComponent(javax.swing.JTree(), DefaultMutableTreeNode(Turn(null, prompt, null)), false, false, true, 0, false)
        val it = r.iterator()
        val out = mutableListOf<Pair<String, Boolean>>()
        while (it.hasNext()) {
            it.next()
            out += it.fragment to (it.textAttributes.style and com.intellij.ui.SimpleTextAttributes.STYLE_BOLD != 0)
        }
        return out
    }

    @Test
    fun `slash command name is bold, args are not`() {
        assertEquals(listOf("/inc" to true, " plan:TODO.md:12" to false), fragments("/inc plan:TODO.md:12"))
    }

    @Test
    fun `bash input is highlighted, plain prompt is not`() {
        assertEquals(listOf("! git status" to true), fragments("! git status"))
        assertEquals(listOf("oprav /tmp cestu" to false), fragments("oprav /tmp cestu"))
    }
}

class SessionPhaseTest {
    private fun text(vararg prompts: String): String {
        val r = Renderer()
        val s = Session("s", "title", prompts.map { Turn(null, it, null) })
        r.getTreeCellRendererComponent(javax.swing.JTree(), DefaultMutableTreeNode(s), false, false, false, 0, false)
        return r.getCharSequence(false).toString()
    }

    @Test
    fun `session row ends with the last slash command`() {
        assertEquals("title   · 3 · /review-fix", text("/inc plan:1", "/review-fix", "fix the typo too"))
    }

    @Test
    fun `session without slash commands has no phase`() {
        assertEquals("title   · 1", text("just a question"))
    }
}
