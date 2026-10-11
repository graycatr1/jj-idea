package `in`.kkkev.jjidea.ui.oplog

import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult.Success.Irreversible.Reason.READ_ONLY
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.OperationId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private const val NUL = "\u0000"

/**
 * Operation-count tests for [OperationLogLoader] (contributing.md § Performance & Scale): opening
 * costs a head probe plus one full query; an unchanged-head refresh costs the probe only.
 */
class OperationLogLoaderTest {
    private fun record(id: String, snapshot: Boolean = false) =
        listOf(id, "", "op $id", "1788000000", "kevin", if (snapshot) "1" else "0", "0", "args")
            .joinToString(NUL, postfix = NUL)

    private fun ok(stdout: String) = CommandExecutor.CommandResult.Success.Irreversible(stdout, "", READ_ONLY)

    private fun entry(id: String, snapshot: Boolean) = OperationEntry(
        OperationId(id),
        emptyList(),
        "op $id",
        Instant.fromEpochSeconds(0),
        "u",
        snapshot,
        false,
        ""
    )

    @Test
    fun `first load probes the head once then runs one full query`() {
        val executor = mockk<CommandExecutor>()
        every { executor.opLog(1, any()) } returns ok(record("b"))
        every { executor.opLog(100, any()) } returns ok(record("b") + record("a"))

        val result = OperationLogLoader(executor).load()

        result.shouldBeInstanceOf<OperationLogLoader.Result.Loaded>()
        result.entries.map { it.id.toString() } shouldContainExactly listOf("b", "a")
        result.changed shouldBe true
        verify(exactly = 1) { executor.opLog(1, any()) }
        verify(exactly = 1) { executor.opLog(100, any()) }
    }

    @Test
    fun `refresh with unchanged head costs the probe only and reports no change`() {
        val executor = mockk<CommandExecutor>()
        every { executor.opLog(1, any()) } returns ok(record("b"))
        every { executor.opLog(100, any()) } returns ok(record("b") + record("a"))
        val loader = OperationLogLoader(executor)
        loader.load()

        val second = loader.load()

        second.shouldBeInstanceOf<OperationLogLoader.Result.Loaded>()
        second.changed shouldBe false
        verify(exactly = 2) { executor.opLog(1, any()) }
        verify(exactly = 1) { executor.opLog(100, any()) }
    }

    @Test
    fun `changed head triggers exactly one more full query`() {
        val executor = mockk<CommandExecutor>()
        every { executor.opLog(1, any()) } returnsMany listOf(ok(record("b")), ok(record("c")))
        every { executor.opLog(100, any()) } returnsMany
            listOf(ok(record("b") + record("a")), ok(record("c") + record("b") + record("a")))
        val loader = OperationLogLoader(executor)
        loader.load()

        val second = loader.load()

        second.shouldBeInstanceOf<OperationLogLoader.Result.Loaded>()
        second.changed shouldBe true
        second.entries.size shouldBe 3
        verify(exactly = 2) { executor.opLog(100, any()) }
    }

    @Test
    fun `failed probe is reported rather than thrown`() {
        val executor = mockk<CommandExecutor>()
        every { executor.opLog(any(), any()) } returns
            CommandExecutor.CommandResult.Failure.Exited(stdout = "", stderr = "boom", exitCode = 1)

        OperationLogLoader(executor).load() shouldBe OperationLogLoader.Result.Failed("boom")
    }

    @Test
    fun `snapshot operations are hidden unless asked for, by flag not by text`() {
        val entries = listOf(entry("c", snapshot = true), entry("b", snapshot = false), entry("a", snapshot = true))

        entries.visibleOperations(showSnapshots = false).map { it.id.toString() } shouldContainExactly listOf("b")
        entries.visibleOperations(showSnapshots = true) shouldBe entries
    }
}
