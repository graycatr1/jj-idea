package `in`.kkkev.jjidea.actions.undo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.oplog.JujutsuOperationLogTabManager
import `in`.kkkev.jjidea.ui.oplog.isOperationLogEnabled
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * Opens the Operations tab (jj-idea-aii0). Hidden entirely unless the
 * [in.kkkev.jjidea.preview.PreviewFeature.OPERATION_LOG] preview feature is enabled, so users
 * without an access code see no trace of it.
 */
class ShowOperationLogAction : DumbAwareAction(
    JujutsuBundle.message("action.show.oplog"),
    JujutsuBundle.message("action.show.oplog.description"),
    AllIcons.Vcs.History
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) = with(e.presentation) {
        isVisible = isOperationLogEnabled()
        isEnabled = e.project.isJujutsu
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = project.stateModel.initialisedRepositories.value.values.firstOrNull() ?: return
        JujutsuOperationLogTabManager.getInstance(project).open(repo)
    }
}
