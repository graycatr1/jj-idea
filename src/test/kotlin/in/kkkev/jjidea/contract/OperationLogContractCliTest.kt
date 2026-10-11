package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.ui.oplog.OperationLogLoader
import `in`.kkkev.jjidea.ui.oplog.visibleOperations
import io.kotest.matchers.collections.shouldContain
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

/**
 * Real-jj verification of the Operations tab's data path (jj-idea-aii0): [OperationLogLoader]
 * against real `jj op log` output, per-entry Revert of an operation the plugin did *not* issue
 * (a terminal `jj describe`), and Restore. [RequiresJj]-gated, CLI-only like [UndoContractCliTest].
 */
@Tag("contract")
@RequiresJj
class OperationLogContractCliTest {
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

    private fun load(loader: OperationLogLoader) =
        loader.load().shouldBeInstanceOf<OperationLogLoader.Result.Loaded>()

    private fun description() = jj.run("log", "--no-graph", "-r", "@", "-T", "description").stdout

    @Test
    fun `loader lists real operations newest first, including the terminal-issued one, and flags root`() {
        jj.describe("from the terminal")

        val entries = load(OperationLogLoader(executor)).entries

        entries.first().args shouldContain "describe"
        entries.last().isRoot shouldBe true
        entries.filter { it.isRoot }.size shouldBe 1
    }

    @Test
    fun `hiding snapshot operations drops only entries the jj snapshot flag marks`() {
        jj.createFile("a.txt", "x")
        jj.describe("after a dirty working copy") // forces a snapshot op before the describe op

        val entries = load(OperationLogLoader(executor)).entries

        val hidden = entries.visibleOperations(showSnapshots = false)
        hidden.none { it.isSnapshot } shouldBe true
        entries.filter { it.isSnapshot }.size shouldBe entries.size - hidden.size
        entries.any { it.isSnapshot } shouldBe true
    }

    @Test
    fun `reverting a terminal-issued operation chosen from the list undoes exactly it`() {
        jj.describe("first")
        jj.describe("second")
        val target = load(OperationLogLoader(executor)).entries.first { it.args.contains("describe") }

        executor.opRevert(target.id).shouldBeInstanceOf<CommandResult.Success>()

        description() shouldNotContain "second"
        description() shouldContain "first"
    }

    @Test
    fun `restore resets the repository to the chosen operation and the loader sees the head change`() {
        jj.describe("first")
        val loader = OperationLogLoader(executor)
        val atFirst = load(loader).entries.first { it.args.contains("describe") }
        jj.describe("second")
        load(loader).changed shouldBe true

        executor.opRestore(atFirst.id).shouldBeInstanceOf<CommandResult.Success>()

        description() shouldContain "first"
        description() shouldNotContain "second"
        val after = load(loader)
        after.changed shouldBe true
        after.entries.map { it.args } shouldContain atFirst.args
        load(loader).changed shouldBe false
    }
}
