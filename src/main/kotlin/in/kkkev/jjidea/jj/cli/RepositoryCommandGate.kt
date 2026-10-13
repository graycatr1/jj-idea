package `in`.kkkev.jjidea.jj.cli

import com.intellij.openapi.vcs.VcsException
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import java.util.concurrent.ConcurrentHashMap

/**
 * jj queries can snapshot and import Git HEAD. Serialize them with mutations, including byte
 * reads, and stop automatic imports after a Git export failure with possible partial effects.
 * Recovery is deliberately external: restarting the IDE resumes commands after manual inspection.
 */
internal class RepositoryCommandGate {
    private var recoveryReason: String? = null

    @Synchronized
    fun execute(invocation: JjInvocation, action: () -> CommandResult): CommandResult {
        blockedMessage(invocation)?.let { return CommandResult.Failure.Blocked(it) }
        val result = action()
        if (result is CommandResult.Failure) recordFailure(result.message)
        return if (result is CommandResult.Failure.Exited && recoveryReason != null) {
            result.copy(stderr = JujutsuBundle.message("cli.error.git.recovery.required", result.message))
        } else {
            result
        }
    }

    @Synchronized
    fun executeBytes(invocation: JjInvocation, action: () -> ByteArray): ByteArray {
        blockedMessage(invocation)?.let { throw VcsException(it) }
        return try {
            action()
        } catch (error: VcsException) {
            recordFailure(error.message.orEmpty())
            blockedMessage(invocation)?.let { throw VcsException(it, error) }
            throw error
        }
    }

    private fun blockedMessage(invocation: JjInvocation): String? {
        // Only explicit read-only, non-snapshotting queries remain safe. In particular,
        // READ_ONLY alone does not mean that jj won't update Git HEAD or the index.
        if (invocation.reversibility == Reversibility.READ_ONLY &&
            "--ignore-working-copy" in invocation.args
        ) {
            return null
        }
        return recoveryReason?.let { JujutsuBundle.message("cli.error.git.recovery.required", it) }
    }

    private fun recordFailure(message: String) {
        if (message.contains("Failed to reset Git HEAD state", ignoreCase = true)) recoveryReason = message
    }

    companion object {
        // Undo-tracking executors and separate panels must share the same gate.
        private val gates = ConcurrentHashMap<String, RepositoryCommandGate>()
        fun forRepository(path: String) = gates.computeIfAbsent(path) { RepositoryCommandGate() }
    }
}
