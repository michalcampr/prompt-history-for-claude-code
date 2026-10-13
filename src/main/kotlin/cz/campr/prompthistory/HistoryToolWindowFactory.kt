package cz.campr.prompthistory

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.ToolTipManager
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class HistoryToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HistoryPanel(
            project.basePath?.let(TranscriptStore::forProject),
            shellPid = terminalShellPid(project),
            claude = ClaudeProcesses.default(),
        )
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        Disposer.register(content, panel::dispose)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(object : ToggleAction("All Sessions", "Show all sessions of the project", AllIcons.Actions.ListFiles) {
            override fun isSelected(e: AnActionEvent) = panel.showAll
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                panel.showAll = state
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }))
    }

    /** The terminal API is experimental; if it fails, the panel keeps working without following the tab. */
    private fun terminalShellPid(project: Project): () -> Long? {
        var broken = false
        return {
            if (broken) null else try {
                TerminalFocus.selectedShellPid(project)
            } catch (e: Throwable) {
                broken = true
                thisLogger().warn("Prompt History: active terminal tab cannot be read, following it is disabled", e)
                null
            }
        }
    }
}

internal class AnswerLine(val text: String)

/**
 * The tree is updated node by node (insert/change/remove), never rebuilt; otherwise every
 * refresh would drop the selection and manually collapsed prompts.
 */
internal class HistoryPanel(
    private val store: TranscriptStore?,
    poll: Boolean = true,
    private val shellPid: () -> Long? = { null },
    private val claude: ClaudeProcesses? = null,
) {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
        emptyText.text = "No Claude Code sessions for this project"
        ToolTipManager.sharedInstance().registerComponent(this)
    }
    val component = ScrollPaneFactory.createScrollPane(tree)

    private var firstLoad = true

    // EDT-only state.
    private var sessions = emptyList<Session>()
    private var focusId: String? = null
    var showAll = false
        set(value) {
            field = value
            rerender()
        }

    // Last shell PID of the active tab; the poller reads it too.
    @Volatile
    private var lastShellPid: Long? = null

    init {
        // An expanded session shows all prompts with answers. Tree collapses the prompts when
        // their session collapses, so a manually collapsed prompt survives a refresh, not that.
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(e: TreeExpansionEvent) {
                val node = e.path.lastPathComponent as DefaultMutableTreeNode
                if (node.userObject is Session) expandTurns(node, 0)
            }

            override fun treeCollapsed(e: TreeExpansionEvent) {}
        })
    }

    private val poller = if (!poll) null else AppExecutorUtil.getAppScheduledExecutorService()
        .scheduleWithFixedDelay(::poll, 0, 3, TimeUnit.SECONDS)

    // Switching terminal tabs shows up within half a second, not with the next poll.
    private val tabWatcher = if (!poll) null else Timer(500) {
        val pid = shellPid()
        if (pid != lastShellPid) {
            lastShellPid = pid
            ApplicationManager.getApplication().executeOnPooledThread { publish(null, resolveFocus(pid)) }
        }
    }.apply { start() }

    fun dispose() {
        poller?.cancel(false)
        tabWatcher?.stop()
    }

    private fun poll() {
        val sessions = try {
            store?.refresh()
        } catch (e: Exception) {
            thisLogger().warn("Prompt History: reading transcripts failed", e)
            null
        }
        // After /clear the same process has a new session, so resolve again even without a tab switch.
        publish(sessions, resolveFocus(lastShellPid))
    }

    private fun resolveFocus(pid: Long?): String? = pid?.let { claude?.sessionIdUnder(it) }

    /** null means no change; a tab without Claude keeps the last shown session. */
    private fun publish(sessions: List<Session>?, focus: String?) {
        if (sessions == null && focus == null) return
        ApplicationManager.getApplication().invokeLater {
            if (sessions == null && focus == focusId) return@invokeLater
            update(sessions ?: this.sessions, focus ?: focusId)
        }
    }

    fun update(sessions: List<Session>, focusId: String?) {
        this.sessions = sessions
        this.focusId = focusId
        rerender()
    }

    private fun rerender() {
        if (showAll) {
            tree.emptyText.text = "No Claude Code sessions for this project"
            render(sessions)
            return
        }
        // No known terminal session: show the most recently active one (the store sorts by activity).
        val shown = if (focusId == null) sessions.take(1) else sessions.filter { it.id == focusId }
        tree.emptyText.text = if (focusId == null) "No Claude Code sessions for this project"
        else "No prompts yet in this Claude session"
        render(shown)
        if (root.childCount > 0) tree.expandPath(TreePath((root.getChildAt(0) as DefaultMutableTreeNode).path))
    }

    /** Sessions by start time, newest on top: new ones appear on top and nothing jumps. */
    fun render(sessions: List<Session>) {
        val wanted = sessions.sortedByDescending { it.turns.first().time ?: Instant.EPOCH }
        val wantedIds = wanted.map { it.id }.toSet()

        for (i in root.childCount - 1 downTo 0) {
            val node = root.getChildAt(i) as DefaultMutableTreeNode
            if ((node.userObject as Session).id !in wantedIds) model.removeNodeFromParent(node)
        }
        val existing = (0 until root.childCount).associateBy(
            { ((root.getChildAt(it) as DefaultMutableTreeNode).userObject as Session).id },
            { root.getChildAt(it) as DefaultMutableTreeNode },
        )
        for ((index, session) in wanted.withIndex()) {
            val node = existing[session.id]
            if (node == null) {
                val sNode = DefaultMutableTreeNode(session)
                model.insertNodeInto(sNode, root, index)
                syncTurns(sNode, session)
            } else {
                if (node.userObject != session) {
                    node.userObject = session
                    model.nodeChanged(node)
                }
                syncTurns(node, session)
            }
        }

        // Without children the hidden root collapses and later sessions would stay invisible.
        if (root.childCount > 0 && !tree.isExpanded(TreePath(root))) tree.expandPath(TreePath(root))
        if (firstLoad && root.childCount > 0) {
            firstLoad = false
            tree.expandPath(TreePath((root.getChildAt(0) as DefaultMutableTreeNode).path))
        }
    }

    private fun syncTurns(sNode: DefaultMutableTreeNode, session: Session) {
        val known = sNode.childCount
        for ((i, turn) in session.turns.withIndex()) {
            if (i < known) {
                val tNode = sNode.getChildAt(i) as DefaultMutableTreeNode
                if (tNode.userObject != turn) {
                    tNode.userObject = turn
                    model.nodeChanged(tNode)
                    syncAnswer(tNode, turn)
                }
            } else {
                val tNode = DefaultMutableTreeNode(turn)
                model.insertNodeInto(tNode, sNode, i)
                syncAnswer(tNode, turn)
            }
        }
        if (tree.isExpanded(TreePath(sNode.path))) expandTurns(sNode, known)
    }

    private fun syncAnswer(tNode: DefaultMutableTreeNode, turn: Turn) {
        val answer = turn.answer ?: return
        if (tNode.childCount == 0) {
            model.insertNodeInto(DefaultMutableTreeNode(AnswerLine(answer)), tNode, 0)
            // An answer arrived for a shown prompt (a leaf so far, so not collapsible): show it.
            val sNode = tNode.parent as DefaultMutableTreeNode
            if (tree.isExpanded(TreePath(sNode.path))) tree.expandPath(TreePath(tNode.path))
        } else {
            val aNode = tNode.getChildAt(0) as DefaultMutableTreeNode
            aNode.userObject = AnswerLine(answer)
            model.nodeChanged(aNode)
        }
    }

    private fun expandTurns(sNode: DefaultMutableTreeNode, from: Int) {
        for (i in from until sNode.childCount) {
            tree.expandPath(TreePath((sNode.getChildAt(i) as DefaultMutableTreeNode).path))
        }
    }
}

internal class Renderer : ColoredTreeCellRenderer() {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateTimeFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        toolTipText = null
        when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
            is Session -> {
                append(oneLine(o.title ?: o.turns.first().prompt), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                val last = o.turns.last().time?.let(::format).orEmpty()
                append("  $last · ${o.turns.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                // Phase of the task: the last slash command, e.g. /review-fix.
                o.turns.lastOrNull { it.prompt.startsWith("/") }?.let {
                    append(" · ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(it.prompt.substringBefore(' '), phaseAttributes)
                }
            }
            is Turn -> {
                o.time?.let { append(format(it) + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                appendPrompt(oneLine(o.prompt))
                toolTipText = o.prompt.take(2000)
            }
            is AnswerLine -> {
                append(oneLine(o.text), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                toolTipText = o.text
            }
        }
    }

    /** Slash command: bold name, plain arguments. A `!` shell command is bold as a whole. */
    private fun appendPrompt(line: String) {
        when {
            line.startsWith("! ") -> append(line, commandAttributes)
            line.startsWith("/") -> {
                val name = line.substringBefore(' ')
                append(name, commandAttributes)
                if (name.length < line.length) append(line.substring(name.length))
            }
            else -> append(line)
        }
    }

    private val commandAttributes =
        SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBUI.CurrentTheme.Link.Foreground.ENABLED)

    private val phaseAttributes =
        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBUI.CurrentTheme.Link.Foreground.ENABLED)

    private fun format(t: Instant): String {
        val local = t.atZone(ZoneId.systemDefault())
        return if (local.toLocalDate() == LocalDate.now()) timeFmt.format(local) else dateTimeFmt.format(local)
    }

    private fun oneLine(s: String) = s.lineSequence().first().take(200)
}
