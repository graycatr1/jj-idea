package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.WorkingCopy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for [abandonArgs] — the argument building logic used by [CliExecutor.abandon].
 */
class CliExecutorAbandonTest {
    private val a = ChangeId("aaa111", "aaa", null)
    private val b = ChangeId("bbb222", "bbb", null)

    @Test
    fun `single revision`() {
        abandonArgs(listOf(a)).args shouldBe listOf("abandon", "-r", "aaa111")
    }

    @Test
    fun `working copy`() {
        abandonArgs(listOf(WorkingCopy)).args shouldBe listOf("abandon", "-r", "@")
    }

    @Test
    fun `multiple revisions use repeated -r in one invocation`() {
        abandonArgs(listOf(a, b)).args shouldBe listOf("abandon", "-r", "aaa111", "-r", "bbb222")
    }

    @Test
    fun `is reversible`() {
        abandonArgs(listOf(a)).reversibility shouldBe Reversibility.REVERSIBLE
    }

    @Test
    fun `fifty revisions are still one invocation`() {
        val args = abandonArgs(List(50) { ChangeId("id$it", "id$it", null) }).args

        args.count { it == "abandon" } shouldBe 1
        args.count { it == "-r" } shouldBe 50
    }
}
