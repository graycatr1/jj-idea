package `in`.kkkev.jjidea.jj.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Tests for the `jj git remote` mutation argument builders used by [CliExecutor]. */
class CliExecutorGitRemoteManageTest {
    @Test
    fun `add passes name then url`() {
        gitRemoteAddArgs("origin", "https://example.com/r.git").args shouldBe
            listOf("git", "remote", "add", "origin", "https://example.com/r.git")
    }

    @Test
    fun `remove passes name`() {
        gitRemoteRemoveArgs("origin").args shouldBe listOf("git", "remote", "remove", "origin")
    }

    @Test
    fun `rename passes old then new`() {
        gitRemoteRenameArgs("origin", "upstream").args shouldBe
            listOf("git", "remote", "rename", "origin", "upstream")
    }

    @Test
    fun `set-url passes name then url`() {
        gitRemoteSetUrlArgs("origin", "git@example.com:r.git").args shouldBe
            listOf("git", "remote", "set-url", "origin", "git@example.com:r.git")
    }

    @Test
    fun `all mutations are irreversible - git config is outside op revert`() {
        listOf(
            gitRemoteAddArgs("a", "u"),
            gitRemoteRemoveArgs("a"),
            gitRemoteRenameArgs("a", "b"),
            gitRemoteSetUrlArgs("a", "u")
        ).forEach { it.reversibility shouldBe Reversibility.IRREVERSIBLE }
    }
}
