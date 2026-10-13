package `in`.kkkev.jjidea.jj.cli

import com.intellij.openapi.vcs.VcsException
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RepositoryCommandGateTest {
    private val exportFailure = CommandResult.Failure.Exited(
        "",
        "Error: Failed to reset Git HEAD state\nCould not acquire lock for index file",
        1
    )
    private val success = CommandResult.Success.Irreversible(
        "",
        "",
        CommandResult.Success.Irreversible.Reason.READ_ONLY
    )

    @Test
    fun `failed export stops snapshots and writes but permits operation log inspection`() {
        val gate = RepositoryCommandGate()
        val failure = gate.execute(JjInvocation(Reversibility.REVERSIBLE, "squash")) { exportFailure }
        failure.stderr shouldContain "Git HEAD may already have changed"
        gate.execute(statusArgs()) { error("status must not import the partially exported HEAD") }
            .let { it is CommandResult.Failure.Blocked } shouldBe true
        gate.execute(JjInvocation(Reversibility.REVERSIBLE, "squash")) { error("must not retry") }
            .let { it is CommandResult.Failure.Blocked } shouldBe true
        gate.execute(opLogArgs(5)) { success } shouldBe success
        shouldThrow<VcsException> {
            gate.executeBytes(JjInvocation(Reversibility.READ_ONLY, "file", "show")) { error("must not snapshot") }
        }.message.orEmpty() shouldContain "paused"
    }

    @Test
    fun `byte-read export failure also stops subsequent text commands`() {
        val gate = RepositoryCommandGate()
        shouldThrow<VcsException> {
            gate.executeBytes(JjInvocation(Reversibility.READ_ONLY, "file", "show")) {
                throw VcsException(exportFailure.stderr)
            }
        }
        gate.execute(statusArgs()) { error("must not snapshot") }
            .let { it is CommandResult.Failure.Blocked } shouldBe true
    }

    @Test
    fun `ordinary command error does not pause repository`() {
        val gate = RepositoryCommandGate()
        val failure = CommandResult.Failure.Exited("", "No such revision", 1)
        gate.execute(statusArgs()) { failure } shouldBe failure
        gate.execute(statusArgs()) { success } shouldBe success
    }

    @Test
    fun `queued query cannot start before failed export has paused the repository`() {
        val gate = RepositoryCommandGate()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val mutation = pool.submit<CommandResult> {
                gate.execute(JjInvocation(Reversibility.REVERSIBLE, "squash")) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    exportFailure
                }
            }
            check(started.await(5, TimeUnit.SECONDS))
            val query = pool.submit<CommandResult> {
                gate.execute(statusArgs()) { error("queued query must not import HEAD") }
            }
            release.countDown()
            mutation.get(5, TimeUnit.SECONDS)
            (query.get(5, TimeUnit.SECONDS) is CommandResult.Failure.Blocked) shouldBe true
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `executors for the same repository share safety state`() {
        val path = "test-repo-${java.util.UUID.randomUUID()}"
        RepositoryCommandGate.forRepository(path).execute(statusArgs()) { exportFailure }
        RepositoryCommandGate.forRepository(path).execute(statusArgs()) { error("must not snapshot") }
            .let { it is CommandResult.Failure.Blocked } shouldBe true
        RepositoryCommandGate.forRepository("$path-other").execute(statusArgs()) { success } shouldBe success
    }
}
