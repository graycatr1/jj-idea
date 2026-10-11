package `in`.kkkev.jjidea.ui.oplog

import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.OperationId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class OperationRowActionsTest {
    private fun entry(id: String, root: Boolean = false, description: String = "op $id") =
        OperationEntry(
            OperationId(id),
            emptyList(),
            description,
            Instant.fromEpochSeconds(1788000000),
            "kevin",
            false,
            root,
            ""
        )

    @Test
    fun `revert is enabled for an ordinary operation`() {
        revertState(entry("a")).enabled shouldBe true
    }

    @Test
    fun `revert and restore are disabled with a reason on the root operation`() {
        val root = entry("0", root = true)

        revertState(root).enabled shouldBe false
        revertState(root).disabledReason shouldBe "The root operation cannot be reverted or restored"
        restoreState(root, head = entry("a")).enabled shouldBe false
    }

    @Test
    fun `both are disabled when nothing is selected`() {
        revertState(null).enabled shouldBe false
        restoreState(null, head = entry("a")).enabled shouldBe false
    }

    @Test
    fun `restoring to the head is disabled as a no-op but reverting it is allowed`() {
        val head = entry("a")

        restoreState(head, head).enabled shouldBe false
        revertState(head).enabled shouldBe true
    }

    @Test
    fun `restore to an earlier operation is enabled`() {
        restoreState(entry("b"), head = entry("a")).enabled shouldBe true
    }

    @Test
    fun `title is the first description line with placeholders for root and blank`() {
        entry("a", description = "rebase 3 commits\nextra detail").title() shouldBe "rebase 3 commits"
        entry("0", root = true, description = "").title() shouldBe "(repository created)"
        entry("b", description = "").title() shouldBe "(no description)"
    }

    @Test
    fun `restore confirmation names the target and how many later operations are discarded`() {
        val message = restoreConfirmMessage(entry("a", description = "describe commit"), laterCount = 4)

        message shouldContain "describe commit"
        message shouldContain "4 later operation(s)"
    }
}
