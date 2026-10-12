package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

/** Squashes the current log selection, shared by the toolbar and context menu. */
class SquashIntoParentAction : DumbAwareAction(
    JujutsuBundle.message("log.action.squash.into.parent"),
    JujutsuBundle.message("log.action.squash.into.parent.tooltip"),
    JujutsuIcons.Squash
) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = squashableEntry(e.logEntry) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (e.project == null) return
        val target = squashableEntry(e.logEntry) ?: return
        openSquashIntoParentDialog(target)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
