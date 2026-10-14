package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProvider
import com.intellij.util.ThreeState
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.LogEntry

/** A session becomes permanently read-only when its working-copy identity or parent baseline changes. */
internal class ParentEditingSession(val workingCopy: LogEntry, val parent: LogEntry) {
    @Volatile var active = true
        private set
    private val invalidationListeners = mutableListOf<() -> Unit>()

    fun matches(wc: LogEntry?, selected: LogEntry?) = active &&
        wc?.repo == workingCopy.repo &&
        wc.id == workingCopy.id &&
        wc.parentIds == workingCopy.parentIds &&
        !wc.hasConflict &&
        selected?.commitId == parent.commitId

    fun onInvalidated(listener: () -> Unit): () -> Unit {
        invalidationListeners.add(listener)
        if (!active) listener()
        return { invalidationListeners.remove(listener) }
    }

    fun invalidate() {
        active = false
        invalidationListeners.toList().forEach { it() }
        invalidationListeners.clear()
    }

    companion object {
        val KEY: Key<ParentEditingSession> = Key.create("jjidea.parentEditingSession")
    }
}

internal class ParentEditingChange(
    before: ContentRevision?,
    after: ContentRevision?,
    val session: ParentEditingSession
) :
    Change(before, after)

/** Opt-in provider: only the left working panel's cumulative changes have this type. */
class ParentEditingDiffRequestProvider : ChangeDiffRequestProvider {
    override fun isEquals(change1: Change, change2: Change) =
        if (change1 is ParentEditingChange && change2 is ParentEditingChange && change1.session !== change2.session) {
            ThreeState.NO
        } else {
            ThreeState.UNSURE
        }
    override fun canCreate(project: Project?, change: Change) = project != null && change is ParentEditingChange

    override fun process(
        presentable: ChangeDiffRequestProducer,
        context: UserDataHolder,
        indicator: ProgressIndicator
    ): DiffRequest {
        val change = presentable.change as ParentEditingChange
        val project = presentable.project
        val factory = DiffContentFactory.getInstance()
        val before = change.beforeRevision?.let {
            val bytes = if (it is ByteBackedContentRevision) {
                it.contentAsBytes
            } else {
                it.content?.toByteArray(
                    Charsets.UTF_8
                )
            }
            factory.createFromBytes(
                project,
                bytes ?: throw DiffRequestProducerException("Could not load the parent baseline for ${it.file.name}"),
                it.file
            )
        } ?: factory.createEmpty()
        val after =
            change.afterRevision?.file?.virtualFile?.let { factory.create(project, it) } ?: factory.createEmpty()
        return SimpleDiffRequest(
            JujutsuBundle.message("workingcopy.parent.diff.tab"),
            before,
            after,
            JujutsuBundle.message("workingcopy.parent.diff.before", change.session.parent.id.short),
            JujutsuBundle.message("workingcopy.parent.diff.current")
        ).apply { putUserData(ParentEditingSession.KEY, change.session) }
    }
}

/** Native arrows consult Editor.isViewer; revoke it even on a pinned preview when the slot changes. */
class ParentEditingDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val session = request.getUserData(ParentEditingSession.KEY) ?: return
        if (viewer !is TwosideTextDiffViewer) return
        val saves = installParentDiffSave(session, viewer.editor2.document, viewer)
        val disconnect = session.onInvalidated {
            saves.cancelAllUpdates()
            viewer.editors.forEach { it.setViewer(true) }
            viewer.rediff()
        }
        Disposer.register(viewer) { disconnect() }
    }
}

/** JJ lists read disk, so cumulative-diff edits must be saved before VFS can refresh the lists. */
internal fun installParentDiffSave(
    session: ParentEditingSession,
    document: Document,
    owner: Disposable,
    save: (Document) -> Unit = { document ->
        val manager = FileDocumentManager.getInstance()
        if (manager.getFile(document) != null) manager.saveDocument(document)
    }
): MergingUpdateQueue {
    val queue = MergingUpdateQueue("jjParentDiffSave", 200, true, null, owner)
    document.addDocumentListener(
        object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!session.active) return
                queue.queue(object : Update("save") {
                    override fun run() {
                        if (session.active) save(document)
                    }
                })
            }
        },
        owner
    )
    return queue
}
