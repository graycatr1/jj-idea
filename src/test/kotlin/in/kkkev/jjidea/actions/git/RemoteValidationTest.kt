package `in`.kkkev.jjidea.actions.git

import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.GitRemote
import `in`.kkkev.jjidea.jj.commandResult
import `in`.kkkev.jjidea.jj.parseGitRemotes
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private class RecordingExecutor(private val failOn: String? = null) : CommandExecutor by mockk(relaxed = true) {
    val calls = mutableListOf<String>()

    private fun record(call: String, name: String) =
        calls.add(call).let { if (failOn == name) commandResult(1, "", "boom") else commandResult(0, "", "") }

    override fun gitRemoteSetUrl(name: String, url: String) = record("set-url $name $url", "set-url")

    override fun gitRemoteRename(oldName: String, newName: String) = record("rename $oldName $newName", "rename")
}

class RemoteValidationTest {
    @Nested
    inner class Names {
        @Test
        fun `accepts ordinary names`() {
            listOf("origin", "up-stream", "my.fork", "a_b", "team/fork").forEach {
                validateRemoteName(it, emptyList()).shouldBeNull()
            }
        }

        @Test
        fun `rejects blank`() {
            validateRemoteName("  ", emptyList()).shouldNotBeNull()
        }

        @Test
        fun `rejects duplicates`() {
            validateRemoteName("origin", listOf("origin", "upstream")).shouldNotBeNull()
        }

        @Test
        fun `rejects names git would refuse`() {
            listOf(
                "has space",
                "a:b",
                "a~b",
                "a^b",
                "a?b",
                "a*b",
                "a[b",
                "a\\b",
                "a..b",
                "a//b",
                "a@{b",
                "-lead",
                ".lead",
                "trail/",
                "trail.",
                "x.lock",
                "tab\there"
            ).forEach { validateRemoteName(it, emptyList()).shouldNotBeNull() }
        }
    }

    @Nested
    inner class Urls {
        @Test
        fun `accepts https ssh scp-like and local paths including spaces`() {
            listOf(
                "https://github.com/a/b.git",
                "ssh://git@host:2222/a/b.git",
                "git@github.com:a/b.git",
                "/tmp/my repo.git",
                "../relative/repo"
            ).forEach { validateRemoteUrl(it).shouldBeNull() }
        }

        @Test
        fun `rejects blank and option-like`() {
            validateRemoteUrl("").shouldNotBeNull()
            validateRemoteUrl("   ").shouldNotBeNull()
            validateRemoteUrl("--upload-pack=evil").shouldNotBeNull()
        }
    }

    @Nested
    inner class Edit {
        private val old = GitRemote("origin", "https://a/x.git")

        @Test
        fun `url only runs set-url alone`() {
            val ex = RecordingExecutor()
            ex.editRemote(old, "origin", "https://a/y.git")
            ex.calls shouldBe listOf("set-url origin https://a/y.git")
        }

        @Test
        fun `name only runs rename alone`() {
            val ex = RecordingExecutor()
            ex.editRemote(old, "upstream", old.url)
            ex.calls shouldBe listOf("rename origin upstream")
        }

        @Test
        fun `both change runs set-url under the old name then rename`() {
            val ex = RecordingExecutor()
            ex.editRemote(old, "upstream", "https://a/y.git")
            ex.calls shouldBe listOf("set-url origin https://a/y.git", "rename origin upstream")
        }

        @Test
        fun `a failing set-url stops before rename`() {
            val ex = RecordingExecutor(failOn = "set-url")
            ex.editRemote(
                old,
                "upstream",
                "https://a/y.git"
            ).shouldBeInstanceOf<CommandExecutor.CommandResult.Failure>()
            ex.calls shouldBe listOf("set-url origin https://a/y.git")
        }
    }

    @Test
    fun `parseGitRemotes reads name and url and skips malformed lines`() {
        parseGitRemotes("origin https://a/x.git\nupstream git@h:a/b.git\n\nbroken\n") shouldBe
            listOf(GitRemote("origin", "https://a/x.git"), GitRemote("upstream", "git@h:a/b.git"))
    }
}
