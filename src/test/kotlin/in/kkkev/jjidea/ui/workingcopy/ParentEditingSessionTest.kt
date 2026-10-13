package `in`.kkkev.jjidea.ui.workingcopy

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class ParentEditingSessionTest {
    private val repo = mockk<JujutsuRepository>()
    private val parent = LogEntry(
        repo,
        ChangeId("parent"),
        CommitId("parent-tree"),
        "Described parent",
        parentIds = listOf(ChangeId("base"))
    )
    private val slot = LogEntry(
        repo,
        ChangeId("slot"),
        CommitId("slot-tree"),
        "",
        parentIds = listOf(parent.id),
        isWorkingCopy = true
    )

    @Test
    fun `only selecting the unique direct parent enables the cumulative list`() {
        cumulativeParent(slot, parent) shouldBe parent
        cumulativeParent(slot, slot) shouldBe null
        cumulativeParent(slot.copy(parentIds = listOf(parent.id, ChangeId("other"))), parent) shouldBe null
        cumulativeParent(slot.copy(repo = mockk()), parent) shouldBe null
    }

    @Test
    fun `snapshotting new edits preserves the live session but switching slots does not`() {
        val session = ParentEditingSession(slot, parent)
        session.matches(slot.copy(commitId = CommitId("edited-tree")), parent) shouldBe true
        session.matches(slot.copy(id = ChangeId("new-slot")), parent) shouldBe false
    }

    @Test
    fun `rewriting the parent or leaving its selection invalidates the baseline`() {
        val session = ParentEditingSession(slot, parent)
        session.matches(slot, parent.copy(commitId = CommitId("rewritten-parent"))) shouldBe false
        session.matches(slot, null) shouldBe false
        session.matches(slot.copy(hasConflict = true), parent) shouldBe false
    }

    @Test
    fun `invalidation locks existing and subsequently opened viewers permanently`() {
        val session = ParentEditingSession(slot, parent)
        var existingLocked = false
        session.onInvalidated { existingLocked = true }
        session.invalidate()
        existingLocked shouldBe true
        var laterLocked = false
        session.onInvalidated { laterLocked = true }
        laterLocked shouldBe true
        session.matches(slot, parent) shouldBe false
    }
}
