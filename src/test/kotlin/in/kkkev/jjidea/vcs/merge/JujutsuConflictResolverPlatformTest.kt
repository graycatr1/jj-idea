package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.conflict.ExtractedConflict
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
class JujutsuConflictResolverPlatformTest {
    private val project = projectFixture()

    @TempDir
    lateinit var directory: Path

    @Test
    fun `merge updates an already cached document and preserves disk line endings`() {
        val path = directory.resolve("conflict.txt")
        Files.writeString(path, "old\r\nconflict\r\n")
        WriteIntentReadAction.run {
            val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
            document.text shouldBe "old\nconflict\n"
            val provider = mockk<JujutsuMergeProvider>(relaxed = true)
            every { provider.loadConflict(file) } returns ExtractedConflict(
                MergeData().apply {
                    CURRENT = "ours".toByteArray()
                    ORIGINAL = "base".toByteArray()
                    LAST = "theirs".toByteArray()
                },
                currentTitle = null,
                lastTitle = null,
                currentIsJjSide1 = true
            )
            JujutsuConflictResolver(
                project.get(),
                provider,
                resolveOne = { _, _ -> "merged\nresult\n".toByteArray() },
                conflictInfoFor = { null }
            ).resolve(listOf(file))

            document.text shouldBe "merged\nresult\n"
            Files.readString(path) shouldBe "merged\r\nresult\r\n"
            FileDocumentManager.getInstance().isDocumentUnsaved(document) shouldBe false
        }
    }
}
