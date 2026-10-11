package `in`.kkkev.jjidea.actions.git

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.preview.PreviewEntitlement
import `in`.kkkev.jjidea.preview.PreviewFeature
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories
import `in`.kkkev.jjidea.vcs.isJujutsu

/** Opens [ManageRemotesDialog] (first repo pre-selected; multi-root projects switch repo inside the dialog). */
class ManageRemotesAction : DumbAwareAction(
    JujutsuBundle.message("action.git.remotes"),
    JujutsuBundle.message("action.git.remotes.description"),
    null
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project.isJujutsu && manageRemotesEnabled()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.initialisedJujutsuRepositories.firstOrNull()?.let { showManageRemotes(project, it) }
    }
}

/** The remotes dialog is early-access ([PreviewFeature.MANAGE_REMOTES]) until it graduates. */
fun manageRemotesEnabled() = PreviewEntitlement.getInstance().isEnabled(PreviewFeature.MANAGE_REMOTES)

/**
 * Opens the management dialog with [repo] selected (the repo drop-down appears when the project has several).
 * Loads [repo]'s remotes off EDT first. Safe to call from any thread.
 */
fun showManageRemotes(project: Project, repo: JujutsuRepository) = runInBackground {
    val remotes = repo.gitRemotes
    val repos = project.initialisedJujutsuRepositories.toList().ifEmpty { listOf(repo) }
    runLater { ManageRemotesDialog(project, repos, repo, remotes).show() }
}
