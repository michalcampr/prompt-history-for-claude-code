package cz.campr.prompthistory

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * PID of the shell in the active terminal tab. Built on the experimental API of the new
 * terminal (TerminalView is also @Internal), so callers must be ready for any Throwable.
 */
internal object TerminalFocus {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun selectedShellPid(project: Project): Long? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return null
        val selected = toolWindow.contentManagerIfCreated?.selectedContent ?: return null
        val tab = TerminalToolWindowTabsManager.getInstance(project).tabs.firstOrNull { it.content == selected }
            ?: return null
        val options = tab.view.startupOptionsDeferred
        return if (options.isCompleted) options.getCompleted().pid else null
    }
}
