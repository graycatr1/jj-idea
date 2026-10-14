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
    fun `an unnamed change is continued even with existing edits`() {
        val slot = entry(description = "", empty = false)
        editingSlot(slot, emptyList()) shouldBe slot
    }

    @Test
    fun `editing a described parent does not resume its nonempty unnamed child`() {
        val parent = entry()
        val slot = entry(id = "slot", description = "", parents = listOf(parent.id), empty = false)
        editingSlot(parent, listOf(slot)).shouldBeNull()
    }

    @Test
    fun `editing a described parent resumes its empty unnamed child`() {
        val parent = entry()
        val slot = entry(id = "slot", description = "", parents = listOf(parent.id))
        editingSlot(parent, listOf(slot)) shouldBe slot
    }

    @Test
    fun `an unrelated empty working copy is not an editing slot`() {
        val parent = entry()
        val unrelated = entry(id = "other", description = "", isWorkingCopy = true)
        editingSlot(parent, listOf(unrelated)).shouldBeNull()
    }

    @Test
    fun `a described empty child is not an editing slot`() {
        val parent = entry()
        val child = entry(id = "child", parents = listOf(parent.id))
        editingSlot(parent, listOf(child)).shouldBeNull()
    }

    @Test
    fun `immutable children are not editing slots`() {
        val parent = entry()
        val child = entry(id = "child", description = "", parents = listOf(parent.id))
        editingSlot(parent, listOf(child.copy(immutable = true))).shouldBeNull()
    }

    @Test
    fun `empty child that inherits parent conflicts is reused`() {
        val parent = entry().copy(hasConflict = true)
        val child = entry(id = "child", description = "", parents = listOf(parent.id)).copy(hasConflict = true)
        editingSlot(parent, listOf(child)) shouldBe child
    }

    @Test
    fun `nonempty conflicted child is not reused`() {
        val parent = entry().copy(hasConflict = true)
        val child = entry(id = "child", description = "", parents = listOf(parent.id), empty = false)
            .copy(hasConflict = true)
        editingSlot(parent, listOf(child)).shouldBeNull()
    }

    @Test
    fun `multiple empty slots prefer current working copy`() {
        val parent = entry()
        val first = entry(id = "first", description = "", parents = listOf(parent.id))
        val current = entry(id = "current", description = "", parents = listOf(parent.id), isWorkingCopy = true)
        editingSlot(parent, listOf(first, current)) shouldBe current
    }

    @Test
    fun `multiple empty slots without a current working copy create a fresh slot`() {
        val parent = entry()
        val first = entry(id = "first", description = "", parents = listOf(parent.id))
        val second = entry(id = "second", description = "", parents = listOf(parent.id))
        editingSlot(parent, listOf(first, second)).shouldBeNull()
    }
}
