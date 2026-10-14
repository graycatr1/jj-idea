package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.WorkingCopy
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Tag("platform")
@TestApplication
@RunInEdt
class ParentEditingDiffPlatformTest {
    private val project = projectFixture()

    @TempDir
    lateinit var directory: Path

    @Test
    fun `parent diff changes save the live file without a manual save`() {
        val path = directory.resolve("parent.txt")
        Files.writeString(path, "parent edit\n")
        WriteIntentReadAction.run {
            val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val manager = FileDocumentManager.getInstance()
            val document = requireNotNull(manager.getDocument(file))
            val repo = mockk<JujutsuRepository>()
            val parent = LogEntry(repo, ChangeId("parent"), CommitId("parent-tree"), "Parent")
            val slot = LogEntry(repo, ChangeId("slot"), CommitId("slot-tree"), "", parentIds = listOf(parent.id))
            val owner = Disposer.newDisposable()
            try {
                val queue = installParentDiffSave(ParentEditingSession(slot, parent), document, owner)
                com.intellij.openapi.application.runWriteAction { document.setText("reverted\n") }
                manager.isDocumentUnsaved(document) shouldBe true
                queue.flush()

                Files.readString(path) shouldBe "reverted\n"
                manager.isDocumentUnsaved(document) shouldBe false
            } finally {
                Disposer.dispose(owner)
            }
        }
    }

    @Test
    fun `invalidated and disposed parent diffs cannot save queued edits`() {
        WriteIntentReadAction.run {
            val repo = mockk<JujutsuRepository>()
            val parent = LogEntry(repo, ChangeId("parent"), CommitId("parent-tree"), "Parent")
            val slot = LogEntry(repo, ChangeId("slot"), CommitId("slot-tree"), "", parentIds = listOf(parent.id))
            val session = ParentEditingSession(slot, parent)
            val document = EditorFactory.getInstance().createDocument("before")
            val owner = Disposer.newDisposable()
            var saves = 0
            val queue = installParentDiffSave(session, document, owner) { saves++ }
            try {
                com.intellij.openapi.application.runWriteAction { document.setText("pending") }
                session.invalidate()
                queue.flush()
                saves shouldBe 0
            } finally {
                Disposer.dispose(owner)
            }
            com.intellij.openapi.application.runWriteAction { document.setText("after disposal") }
            saves shouldBe 0
        }
    }

    @Test
    fun `clearing or multi selecting log rows revokes the previous editing session`() {
        WriteIntentReadAction.run {
            val repo = mockk<JujutsuRepository>()
            val parent = LogEntry(
                repo,
                ChangeId("parent"),
                CommitId("parent-tree"),
                "Parent",
                parentIds = listOf(ChangeId("base"))
            )
            val slot = LogEntry(
                repo,
                ChangeId("slot"),
                CommitId("slot-tree"),
                "",
                parentIds = listOf(parent.id),
                isWorkingCopy = true
            )
            val log = mockk<LogService>()
            every { repo.logService } returns log
            every { log.getLogBasic(Expression("@ | parent"), emptyList(), null) } returns
                Result.success(listOf(slot, parent))
            every { log.getFileChangesBetween(parent.parentContentLocator, WorkingCopy) } returns
                Result.success(emptyList())
            val panel = ParentChangesPanel(project.get(), { repo }, { slot })
            try {
                for (selection in listOf(emptyList(), listOf(parent, slot))) {
                    panel.updateSelection(listOf(parent))
                    val session = requireNotNull(panel.editingSessionForTest)
                    session.active shouldBe true
                    panel.updateSelection(selection)
                    session.active shouldBe false
                    panel.editingSessionForTest shouldBe null
                }
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun `native parent revert changes the live slot and an invalidated viewer becomes read only`() {
        WriteIntentReadAction.run {
            val repo = mockk<JujutsuRepository>()
            val parent = LogEntry(repo, ChangeId("parent"), CommitId("parent-tree"), "Parent")
            val slot = LogEntry(repo, ChangeId("slot"), CommitId("slot-tree"), "", parentIds = listOf(parent.id))
            val session = ParentEditingSession(slot, parent)
            val factory = DiffContentFactory.getInstance()
            val before = factory.create(project.get(), "one\ntwo\nthree\n", PlainTextFileType.INSTANCE)
            val current = factory.createEditable(project.get(), "one\nparent edit\nthree\n", PlainTextFileType.INSTANCE)
            val request = SimpleDiffRequest("Cumulative", before, current, "Before parent", "Working copy")
            request.putUserData(ParentEditingSession.KEY, session)
            val context = TestContext(project.get())
            val viewer = SimpleDiffViewer(context, request)
            try {
                viewer.init()
                ParentEditingDiffExtension().onViewerCreated(viewer, context, request)
                viewer.rediff(true)
                DiffUtil.isEditable(viewer.editor2) shouldBe true
                WriteIntentReadAction.run {
                    DiffUtil.executeWriteCommand(current.document, project.get(), "Revert parent hunk") {
                        viewer.replaceChange(viewer.diffChanges.single(), Side.LEFT)
                    }
                }
                current.document.text shouldBe "one\ntwo\nthree\n"
                session.invalidate()
                DiffUtil.isEditable(viewer.editor2) shouldBe false
            } finally {
                Disposer.dispose(viewer)
            }
        }
    }

    private class TestContext(private val project: Project) : DiffContext() {
        private val data = UserDataHolderBase()
        override fun getProject() = project
        override fun isWindowFocused() = true
        override fun isFocusedInWindow() = true
        override fun requestFocusInWindow() = Unit
        override fun <T : Any?> getUserData(key: Key<T>): T? = data.getUserData(key)
        override fun <T : Any?> putUserData(key: Key<T>, value: T?) = data.putUserData(key, value)
    }
}
