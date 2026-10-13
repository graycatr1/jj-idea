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
    fun `an unnamed change is continued without creating a child even with existing edits`() {
        val slot = entry(description = "", empty = false)
        editingChoices(slot, slot) shouldBe listOf(EditingChoice(slot, false))
    }

    @Test
    fun `editing a described parent proposes continuing its slot or explicitly choosing a new base`() {
        val parent = entry()
        val slot = entry(id = "slot", description = "", parents = listOf(parent.id), empty = false)
        editingChoices(parent, slot) shouldBe listOf(
            EditingChoice(slot, false),
            EditingChoice(slot, true),
            EditingChoice(parent, true)
        )
    }

    @Test
    fun `an unrelated working copy is not proposed as the base for editing a parent`() {
        val parent = entry()
        val unrelated = entry(id = "other", description = "", empty = false)
        editingChoices(parent, unrelated) shouldBe listOf(EditingChoice(parent, true))
    }

    @Test
    fun `a described working copy is not silently treated as an editing slot`() {
        val parent = entry()
        val child = entry(id = "child", parents = listOf(parent.id))
        editingChoices(parent, child) shouldBe listOf(EditingChoice(parent, true))
    }
}
