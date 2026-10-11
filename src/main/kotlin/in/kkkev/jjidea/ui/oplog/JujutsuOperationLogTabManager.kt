package `in`.kkkev.jjidea.ui.oplog

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.preview.PreviewEntitlement
import `in`.kkkev.jjidea.preview.PreviewFeature
import `in`.kkkev.jjidea.util.runLater

/** Whether the Operations tab is currently enabled for this machine (preview-gated, jj-idea-aii0). */
internal fun isOperationLogEnabled() = PreviewEntitlement.getInstance().isEnabled(PreviewFeature.OPERATION_LOG)

/**
 * Opens (or re-selects) the single "Operations" tab in the VCS tool window. Follows
 * [in.kkkev.jjidea.ui.history.JujutsuFileHistoryTabManager]'s lifecycle, including detaching the
 * content on dispose so a dynamic plugin unload doesn't leave a stale panel (jj-idea-nd8x).
 * Refuses to open unless the [PreviewFeature.OPERATION_LOG] preview feature is enabled.
 */
@Service(Service.Level.PROJECT)
internal class JujutsuOperationLogTabManager(private val project: Project) : Disposable {
    private val log = Logger.getInstance(javaClass)
    private var tab: Content? = null

    fun open(repo: JujutsuRepository) {
        if (!isOperationLogEnabled()) return
        runLater {
            val manager = ChangesViewContentManager.getInstance(project)
            val existing = tab
            if (existing != null) {
                manager.setSelectedContent(existing)
                ChangesViewContentManager.getToolWindowFor(project, existing.displayName)?.activate(null)
                return@runLater
            }
            val panel = JujutsuOperationLogPanel(project, repo)
            Disposer.register(this, panel)
            val content = ContentFactory.getInstance()
                .createContent(panel, JujutsuBundle.message("oplog.tab.title"), false)
                .apply {
                    isCloseable = true
                    preferredFocusableComponent = panel
                    setDisposer { tab = null }
                }
            manager.addContent(content)
            manager.setSelectedContent(content)
            ChangesViewContentManager.getToolWindowFor(project, content.displayName)?.activate(null)
            tab = content
        }
    }

    override fun dispose() {
        val content = tab ?: return
        tab = null
        try {
            content.manager?.removeContent(content, false)
        } catch (e: Exception) {
            log.warn("Failed to remove Operations tab from content manager", e)
        }
    }

    companion object {
        fun getInstance(project: Project): JujutsuOperationLogTabManager = project.service()
    }
}
