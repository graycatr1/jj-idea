package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Real-jj check that abandoning several changes is one operation (jj-idea-thk). CLI-only: JjStub handles one `-r`. */
@Tag("contract")
@RequiresJj
class AbandonMultipleContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli
    private lateinit var executor: CliExecutor

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
        val root = mockk<VirtualFile> { every { path } returns tempDir.toString() }
        executor = CliExecutor(root)
    }

    private fun descriptions() = jj.run("log", "-r", "all()", "--no-graph", "-T", "description ++ \"|\"").stdout

    private fun opCount() =
        jj.run("op", "log", "--no-graph", "-T", "id.short() ++ \"\\n\"").stdout.lines().count { it.isNotBlank() }

    @Test
    fun `abandons several changes in one operation and rebases the survivor`() {
        jj.describe("keep")
        jj.newChange("drop-1")
        jj.newChange("drop-2")
        jj.newChange("child")
        val ids = listOf("@-", "@--").map { rev ->
            jj.run("log", "-r", rev, "--no-graph", "-T", "change_id").stdout.trim()
        }
        val opsBefore = opCount()

        val result = executor.withUndoTracking().abandon(ids.map { ChangeId(it) })

        result.shouldBeInstanceOf<CommandResult.Success.Reversible>()
        val after = descriptions()
        after shouldContain "keep"
        after shouldContain "child"
        after shouldNotContain "drop-1"
        after shouldNotContain "drop-2"
        // child was rebased onto "keep"
        jj.run("log", "-r", "@-", "--no-graph", "-T", "description").stdout.trim() shouldBe "keep"
        // one abandon operation (plus at most one paired snapshot)
        (opCount() - opsBefore <= 2) shouldBe true
    }
}
