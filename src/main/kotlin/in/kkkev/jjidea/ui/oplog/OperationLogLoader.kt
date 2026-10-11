package `in`.kkkev.jjidea.ui.oplog

import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.cli.OP_LOG_TEMPLATE
import `in`.kkkev.jjidea.jj.cli.parseOpLog

/**
 * Loads one repository's operation log, skipping the full reload when nothing changed: [load]
 * first probes the head with `jj op log -n1` and only re-runs the full [limit]-sized query when
 * the head operation differs from the cached one. A refresh with an unchanged head therefore costs
 * exactly one tiny call and zero reparsing; a first load or a changed head costs the probe plus one
 * full query. No per-operation subprocess - every field comes from the single [OP_LOG_TEMPLATE].
 *
 * Not thread-safe; call from a single background context (the panel serialises its refreshes).
 */
internal class OperationLogLoader(private val executor: CommandExecutor, private val limit: Int = DEFAULT_LIMIT) {
    private var cached: List<OperationEntry>? = null

    sealed interface Result {
        data class Loaded(val entries: List<OperationEntry>, val changed: Boolean) : Result

        data class Failed(val message: String) : Result
    }

    fun load(): Result {
        val probe = executor.opLog(1, OP_LOG_TEMPLATE)
        if (probe !is CommandExecutor.CommandResult.Success) return Result.Failed(probe.stderr)
        val head = parseOpLog(probe.stdout).firstOrNull()?.id
        val current = cached
        if (current != null && head != null && current.firstOrNull()?.id == head) {
            return Result.Loaded(current, changed = false)
        }

        val full = executor.opLog(limit, OP_LOG_TEMPLATE)
        if (full !is CommandExecutor.CommandResult.Success) return Result.Failed(full.stderr)
        val entries = parseOpLog(full.stdout)
        cached = entries
        return Result.Loaded(entries, changed = true)
    }

    companion object {
        const val DEFAULT_LIMIT = 100
    }
}

/** The "Show snapshot operations" filter: pure, keyed on the parsed `snapshot` flag, never on description text. */
internal fun List<OperationEntry>.visibleOperations(showSnapshots: Boolean): List<OperationEntry> =
    if (showSnapshots) this else filterNot { it.isSnapshot }
