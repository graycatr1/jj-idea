package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.runRecoverableInBackground
import `in`.kkkev.jjidea.util.runLater

fun editableEntry(entry: LogEntry?): LogEntry? = entry?.takeIf { !it.immutable }

/** A described change reuses only an empty slot; selecting an unnamed change resumes its work. */
internal fun editingSlot(target: LogEntry, children: List<LogEntry>): LogEntry? {
    if (target.description.actual.isBlank()) return target
    val slots = children.filter {
        it.repo == target.repo &&
            !it.immutable &&
            it.description.actual.isBlank() &&
            it.parentIds == listOf(target.id) &&
            it.resolveEmpty()
    }
    // Avoid choosing arbitrarily between multiple abandoned slots.
    return slots.singleOrNull { it.isWorkingCopy } ?: slots.singleOrNull()
}

/** Resumes the selected unnamed change or edits a described change in an empty child slot. */
class EditChangeAction : DumbAwareAction(
    JujutsuBundle.message("log.action.edit"),
    JujutsuBundle.message("log.action.edit.tooltip"),
    AllIcons.Actions.Edit
) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = editableEntry(e.logEntry) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (e.project == null) return
        val target = editableEntry(e.logEntry) ?: return

        fun resume(slot: LogEntry?) {
            target.repo.createCommand(switchesWorkingCopy = true) {
                if (slot == null) new(Description.EMPTY, listOf(target.id)) else edit(slot.id)
            }.onSuccess {
                invalidate(select = WorkingCopy, vfsChanged = true)
            }.onFailure {
                tellUser("log.action.edit.error")
            }.executeAsync()
        }

        if (target.description.actual.isBlank()) {
            resume(target)
            return
        }

        fun findSlot() {
            target.repo.runRecoverableInBackground(retry = ::findSlot) {
                val children = target.repo.logService.getLogBasic(
                    Expression("children(${target.id.full})")
                ).getOrThrow()
                val slot = editingSlot(target, children)
                runLater { resume(slot) }
            }
        }
        findSlot()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
