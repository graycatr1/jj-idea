package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.OperationId
import `in`.kkkev.jjidea.jj.Revset
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ResolutionReturnTest {
    @TempDir
    lateinit var directory: Path

    private val repo = mockk<JujutsuRepository>()
    private val executor = mockk<CommandExecutor>()
    private val parent = LogEntry(repo, ChangeId("parent", "p", null), CommitId("parent-hash"), "Original description")
    private val source = LogEntry(
        repo,
        ChangeId("source", "s", null),
        CommitId("source-hash"),
        "",
        parentIds = listOf(parent.id),
        isWorkingCopy = true
    )

    /** Expression's inline representation is unsupported by mockk matchers; use a small fake. */
    private fun live(entries: List<LogEntry> = listOf(source, parent)) {
        val logService = object : LogService by mockk(relaxed = true) {
            override fun getLogBasic(revset: Revset, filePaths: List<FilePath>, limit: Int?) = Result.success(entries)
        }
        every { repo.logService } returns logService
        every { repo.commandExecutor } returns executor
        every { repo.directory } returns mockk { every { path } returns directory.toString() }
    }

    private fun selected(name: String = "resolved.txt", deleted: Boolean = false): SelectedResolution {
        val path = directory.resolve(name)
        if (!deleted) Files.writeString(path, "user edit\nkeep\nresolved\n")
        val file = mockk<VirtualFile> {
            every { toNioPath() } returns path
            every { getPath() } returns path.toString()
        }
        val context =
            ResolutionReturn(repo, source, parent, file, "user edit\nkeep\nconflict\n", "original\nkeep\nconflict\n")
        return SelectedResolution(context, "original\nkeep\nresolved\n", "user edit\nkeep\nresolved\n", deleted)
    }

    private fun refuses(selected: SelectedResolution) {
        executeResolutionReturn(listOf(selected)).shouldBeInstanceOf<CommandResult.Failure.Blocked>()
        verify(exactly = 0) { executor.squashIntoInteractive(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `refuses after switching working copy`() {
        live(listOf(source.copy(id = ChangeId("other", "o", null)), parent))
        refuses(selected())
    }

    @Test
    fun `refuses after source is moved under another parent`() {
        live(listOf(source.copy(parentIds = listOf(ChangeId("other", "o", null))), parent))
        refuses(selected())
    }

    @Test
    fun `refuses when target parent has changed or become immutable`() {
        val selected = selected()
        live(listOf(source, parent.copy(commitId = CommitId("rebased-parent"))))
        refuses(selected)
        live(listOf(source, parent.copy(immutable = true)))
        refuses(selected)
    }

    @Test
    fun `refuses after further edits to resolved file`() {
        live()
        val selected = selected()
        Files.writeString(selected.context.file.toNioPath(), "more user edits")
        refuses(selected)
    }

    @Test
    fun `refuses if deleted resolution file has been recreated`() {
        live()
        val selected = selected(deleted = true)
        Files.writeString(selected.context.file.toNioPath(), "recreated")
        refuses(selected)
    }

    @Test
    fun `validates every file before starting the squash`() {
        live()
        val first = selected()
        val second = selected("second.txt")
        Files.delete(second.context.file.toNioPath())

        executeResolutionReturn(listOf(first, second)).shouldBeInstanceOf<CommandResult.Failure.Blocked>()
        verify(exactly = 0) { executor.squashIntoInteractive(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `refuses mixing resolutions captured from different parent versions`() {
        live()
        val first = selected()
        val second = selected("second.txt")
        val stale = second.copy(context = second.context.copy(parent = parent.copy(commitId = CommitId("old-parent"))))

        executeResolutionReturn(listOf(first, stale)).shouldBeInstanceOf<CommandResult.Failure.Blocked>()
        verify(exactly = 0) { executor.squashIntoInteractive(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `returns multiple selected resolutions in one squash without unrelated paths or user edits`() {
        live()
        val first = selected()
        val second = selected("deleted.txt", deleted = true)
        Files.writeString(directory.resolve("unrelated.txt"), "other user modification")
        val result = CommandResult.Success.Reversible("", "", OperationId("return-resolution"))
        every {
            executor.squashIntoInteractive(source.id, parent.id, parent.description, true, any(), any())
        } answers {
            val config = arg<List<String>>(4)
            val editArgs = config[1].substringAfter("edit-args=")
            val values = editArgs.trim('[', ']').split(",").map { it.trim('"') }
            val staging = Path.of(values[values.indexOf("-cp") + 3])
            Files.readString(staging.resolve("resolved.txt")) shouldBe "original\nkeep\nresolved\n"
            Files.readString(staging.resolve(".jj-idea-deleted")) shouldBe "deleted.txt"
            Files.exists(staging.resolve("unrelated.txt")) shouldBe false
            result
        }

        executeResolutionReturn(listOf(first, second)) shouldBe result

        verify(exactly = 1) {
            executor.squashIntoInteractive(source.id, parent.id, parent.description, true, any(), any())
        }
        Files.readString(first.context.file.toNioPath()) shouldBe first.after
        Files.readString(directory.resolve("unrelated.txt")) shouldBe "other user modification"
    }
}
