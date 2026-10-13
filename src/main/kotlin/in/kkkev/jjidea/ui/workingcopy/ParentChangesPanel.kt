package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.runRecoverableInBackground
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.common.JujutsuChangesTree
import `in`.kkkev.jjidea.ui.common.JujutsuEditorTabDiffPreview
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import javax.swing.JPanel

/** Only a selected, single-parent change directly below the working copy supplies this baseline. */
internal fun cumulativeParent(workingCopy: LogEntry?, selected: LogEntry?): LogEntry? =
    selected?.takeIf {
        workingCopy != null &&
            it.repo == workingCopy.repo &&
            workingCopy.parentIds == listOf(it.id) &&
            it.parentIds.size == 1 &&
            !workingCopy.hasConflict &&
            !it.hasConflict
    }

/** A live cumulative view; the log's historical changes tree remains independent and read-only. */
internal class ParentChangesPanel(
    private val project: Project,
    private val boundRepository: () -> JujutsuRepository?,
    private val workingCopyFor: (JujutsuRepository) -> LogEntry? = {
        project.stateModel.workingCopies.value[it.directory.path]
    }
) : JPanel(BorderLayout()), Disposable, UiDataProvider {
    private val tree = JujutsuChangesTree(project).apply { showsLocalFiles = { true } }
    private val preview =
        JujutsuEditorTabDiffPreview(tree, contextLabel = { JujutsuBundle.message("workingcopy.parent.diff.tab") })
    private val heading = JBLabel().apply { border = JBUI.Borders.empty(4) }
    private var selected: LogEntry? = null
    private var generation = 0
    private var session: ParentEditingSession? = null
    private var switchingRepo: JujutsuRepository? = null
    var onVisibilityChanged: (Boolean) -> Unit = {}

    init {
        add(heading, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        Disposer.register(this, preview)
        tree.additionalDataProvider = { sink ->
            session?.workingCopy?.let { sink[JujutsuDataKeys.LOG_ENTRY] = it }
            sink[DiffDataKeys.EDITOR_TAB_DIFF_PREVIEW] = preview
        }
        tree.installHandlers()
        project.stateModel.logSelection.connect(this) { updateSelection(it) }
        project.stateModel.workingCopies.connect(this) { reload() }
        project.stateModel.logRefresh.connect(this) { reload() }
        project.stateModel.workingCopySwitchStarting.connect(this) { repo ->
            switchingRepo = repo
            if (selected?.repo == repo) invalidateView()
        }
        project.stateModel.workingCopySwitchFinished.connect(this) { repo ->
            if (switchingRepo == repo) switchingRepo = null
            reload()
        }
    }

    internal fun updateSelection(entries: List<LogEntry>) {
        selected = entries.singleOrNull()
        reload()
    }

    internal val editingSessionForTest get() = session

    fun reload() {
        if (switchingRepo != null && switchingRepo == selected?.repo) return
        val wc = selected?.repo?.takeIf { it == boundRepository() }
            ?.let(workingCopyFor)
        val parent = cumulativeParent(wc, selected)
        // An ordinary snapshot preserves the session: the right side is already a live Document.
        if (session?.matches(wc, parent) != true) invalidateView()
        if (wc == null || parent == null) return
        val ticket = ++generation
        val editingSession = session ?: ParentEditingSession(wc, parent).also { session = it }
        parent.repo.runRecoverableInBackground(retry = ::reload) {
            val changes = try {
                // The state-model cache can still describe the old slot immediately after a switch.
                val fresh = parent.repo.logService.getLogBasic(Expression("@ | ${parent.id.full}"))
                    .getOrElse { error ->
                        if (error is ProcessCanceledException) throw error
                        throw VcsException(error.message.orEmpty(), error)
                    }
                if (fresh.singleOrNull { it.isWorkingCopy }?.id != wc.id ||
                    fresh.singleOrNull { it.id == parent.id }?.commitId != parent.commitId
                ) {
                    runLater { if (ticket == generation) invalidateView() }
                    return@runRecoverableInBackground
                }
                parent.repo.logService.getFileChangesBetween(parent.parentContentLocator, WorkingCopy)
                    .getOrElse { error ->
                        if (error is ProcessCanceledException) throw error
                        throw VcsException(error.message.orEmpty(), error)
                    }.map { change ->
                        ParentEditingChange(
                            change.before?.let(parent.repo::createContentRevision),
                            change.after?.let { CurrentContentRevision(it.filePath) },
                            editingSession
                        )
                    }
            } catch (error: VcsException) {
                runLater { if (ticket == generation) invalidateView() }
                throw error
            }
            runLater {
                if (ticket != generation || !editingSession.active) return@runLater
                heading.text = JujutsuBundle.message("workingcopy.parent.changes", parent.id.short)
                tree.setChangesToDisplay(changes)
                tree.invokeAfterRefresh { tree.treeExpander.expandAll() }
                onVisibilityChanged(true)
            }
        }
    }

    private fun invalidateView() {
        generation++
        session?.invalidate()
        session = null
        preview.closePreview()
        tree.setChangesToDisplay(emptyList())
        onVisibilityChanged(false)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        tree.uiDataSnapshot(sink)
    }

    override fun dispose() = invalidateView()
}
