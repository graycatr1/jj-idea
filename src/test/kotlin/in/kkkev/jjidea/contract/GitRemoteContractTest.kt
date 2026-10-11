package `in`.kkkev.jjidea.contract

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

abstract class GitRemoteContractTest {
    @TempDir
    lateinit var tempDir: Path
    lateinit var jj: JjBackend

    abstract fun createBackend(tempDir: Path): JjBackend

    @BeforeEach
    fun setUp() {
        jj = createBackend(tempDir)
        jj.init()
    }

    @Test
    fun `git remote list on fresh repo returns empty output`() {
        val result = jj.run("git", "remote", "list")
        result.isSuccess shouldBe true
        result.stdout.trim() shouldBe ""
    }

    @Test
    fun `git remote list shows added remote`() {
        val remoteUrl = tempDir.resolve("remote.git").toString()
        jj.addGitRemote("origin", remoteUrl)

        val result = jj.run("git", "remote", "list")
        result.isSuccess shouldBe true
        result.stdout shouldContain "origin"
        result.stdout shouldContain remoteUrl
    }

    @Test
    fun `git remote list shows multiple remotes`() {
        val url1 = tempDir.resolve("remote1.git").toString()
        val url2 = tempDir.resolve("remote2.git").toString()
        jj.addGitRemote("origin", url1)
        jj.addGitRemote("upstream", url2)

        val result = jj.run("git", "remote", "list")
        result.isSuccess shouldBe true
        result.stdout shouldContain "origin"
        result.stdout shouldContain "upstream"
        result.stdout shouldContain url1
        result.stdout shouldContain url2
    }

    @Test
    fun `git remote remove deletes the remote`() {
        jj.addGitRemote("origin", "https://example.com/a.git")
        jj.addGitRemote("upstream", "https://example.com/b.git")

        jj.run("git", "remote", "remove", "origin").isSuccess shouldBe true

        val list = jj.run("git", "remote", "list").stdout
        list shouldNotContain "origin"
        list shouldContain "upstream"
    }

    @Test
    fun `git remote remove of unknown remote fails`() {
        val result = jj.run("git", "remote", "remove", "nope")
        result.isSuccess shouldBe false
        result.stderr shouldContain "nope"
    }

    @Test
    fun `git remote rename keeps the url`() {
        jj.addGitRemote("origin", "https://example.com/a.git")

        jj.run("git", "remote", "rename", "origin", "upstream").isSuccess shouldBe true

        val list = jj.run("git", "remote", "list").stdout
        list shouldNotContain "origin"
        list shouldContain "upstream https://example.com/a.git"
    }

    @Test
    fun `git remote rename onto an existing name fails`() {
        jj.addGitRemote("origin", "https://example.com/a.git")
        jj.addGitRemote("upstream", "https://example.com/b.git")

        jj.run("git", "remote", "rename", "origin", "upstream").isSuccess shouldBe false
    }

    @Test
    fun `git remote set-url changes the url`() {
        jj.addGitRemote("origin", "https://example.com/a.git")

        jj.run("git", "remote", "set-url", "origin", "https://example.com/z.git").isSuccess shouldBe true

        jj.run("git", "remote", "list").stdout shouldContain "origin https://example.com/z.git"
    }

    @Test
    fun `git remote add of an existing name fails`() {
        jj.addGitRemote("origin", "https://example.com/a.git")

        jj.run("git", "remote", "add", "origin", "https://example.com/b.git").isSuccess shouldBe false
    }
}
