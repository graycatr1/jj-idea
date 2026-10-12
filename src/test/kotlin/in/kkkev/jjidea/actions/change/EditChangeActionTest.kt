package `in`.kkkev.jjidea.actions.change

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [editableEntry], the pure logic that decides whether the log selection can be
 * edited in a new child change. Immutable changes are excluded because the child will
 * be squashed back into the selected change.
 */
class EditChangeActionTest {
    private val repo = mockk<JujutsuRepository>()

    private fun entry(
        isWorkingCopy: Boolean = false,
        immutable: Boolean = false,
        id: String = "abc123",
        description: String = "Test commit",
        parents: List<ChangeId> = emptyList(),
        empty: Boolean = true
    ) = LogEntry(
        repo = repo,
        id = ChangeId(id, id.take(2), null),
        commitId = CommitId("0000000000000000000000000000000000000000"),
        underlyingDescription = description,
        bookmarks = emptyList(),
        parentIds = parents,
        isWorkingCopy = isWorkingCopy,
        hasConflict = false,
        isEmpty = empty,
        authorTimestamp = null,
        committerTimestamp = null,
        author = null,
        committer = null,
        immutable = immutable
    )

    @Test
    fun `mutable non-working-copy entry is editable`() {
        val e = entry(isWorkingCopy = false, immutable = false)

        val target = editableEntry(e)

        target.shouldNotBeNull()
        target shouldBe e
    }

    @Test
    fun `working copy entry can be edited in a new child`() {
        val target = entry(isWorkingCopy = true)
        editableEntry(target) shouldBe target
    }

    @Test
    fun `immutable entry is not editable`() {
        editableEntry(entry(immutable = true)).shouldBeNull()
    }

    @Test
    fun `no selection is not editable`() {
        editableEntry(null).shouldBeNull()
    }

    @Test
    fun `returning to a described parent resumes its unnamed child with existing edits`() {
        val parent = entry()
        val slot = entry(id = "slot", description = "", parents = listOf(parent.id), empty = false)

        editingSlots(parent, listOf(slot)) shouldBe listOf(slot)
    }

    @Test
    fun `an empty unnamed child is reusable too`() {
        val parent = entry()
        val slot = entry(id = "slot", description = "", parents = listOf(parent.id))

        editingSlots(parent, listOf(slot)) shouldBe listOf(slot)
    }

    @Test
    fun `selecting an unnamed slot resumes itself rather than creating another child`() {
        val slot = entry(description = "", empty = false)
        val child = entry(id = "child", description = "", parents = listOf(slot.id))

        editingSlots(slot, listOf(child)) shouldBe listOf(slot)
    }

    @Test
    fun `described children are independent changes rather than editing slots`() {
        val parent = entry()
        val child = entry(id = "child", parents = listOf(parent.id))

        editingSlots(parent, listOf(child)) shouldBe emptyList()
    }

    @Test
    fun `merge children and children of other parents are not reusable slots`() {
        val parent = entry()
        val other = entry(id = "other")
        val merge = entry(id = "merge", description = "", parents = listOf(parent.id, other.id))
        val unrelated = entry(id = "unrelated", description = "", parents = listOf(other.id))

        editingSlots(parent, listOf(merge, unrelated)) shouldBe emptyList()
    }

    @Test
    fun `immutable unnamed children are not editing slots`() {
        val parent = entry()
        val child = entry(id = "child", description = "", parents = listOf(parent.id), immutable = true)

        editingSlots(parent, listOf(child)) shouldBe emptyList()
    }

    @Test
    fun `multiple slots remain candidates so the user can choose rather than create another`() {
        val parent = entry()
        val first = entry(id = "first", description = "", parents = listOf(parent.id), empty = false)
        val second = entry(id = "second", description = "", parents = listOf(parent.id))

        editingSlots(parent, listOf(first, second)) shouldBe listOf(first, second)
    }

    @Test
    fun `no existing child leaves the action free to create the first slot`() {
        editingSlots(entry(), emptyList()) shouldBe emptyList()
    }
}
