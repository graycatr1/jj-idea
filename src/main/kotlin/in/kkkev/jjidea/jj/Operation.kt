package `in`.kkkev.jjidea.jj

import kotlinx.datetime.Instant

/**
 * Identifies one entry in `jj op log`. Never a [Revision] - it never goes through `-r`, only
 * through `jj op revert <id>` / `jj op log --at-operation`.
 */
@JvmInline
value class OperationId(private val id: String) {
    override fun toString() = id
}

/**
 * One parsed entry from `jj op log`. [time] is the operation's start time, parsed from epoch seconds like the log's
 * timestamps; [user] stays jj's raw `user@host` string.
 * [args] is the operation's raw argv (via the `tags` template keyword) - see
 * [in.kkkev.jjidea.jj.cli.OP_LOG_TEMPLATE] for why `tags` rather than `self.attributes()`.
 */
data class OperationEntry(
    val id: OperationId,
    val parents: List<OperationId>,
    val description: String,
    val time: Instant,
    val user: String,
    val isSnapshot: Boolean,
    val isRoot: Boolean,
    val args: String
)
