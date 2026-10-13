package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.stateModel

fun editableEntry(entry: LogEntry?): LogEntry? = entry?.takeIf { !it.immutable }

internal data class EditingChoice(val entry: LogEntry, val createChild: Boolean)

/** Continuing a slot needs no new change; creating one always requires an explicit choice. */
internal fun editingChoices(target: LogEntry, workingCopy: LogEntry?): List<EditingChoice> {
    if (target.description.actual.isBlank()) return listOf(EditingChoice(target, false))
    val currentSlot = workingCopy?.takeIf {
        it.repo == target.repo && it.description.actual.isBlank() && it.parentIds == listOf(target.id)
    }
    return if (currentSlot == null) {
        listOf(EditingChoice(target, true))
    } else {
        listOf(EditingChoice(currentSlot, false), EditingChoice(currentSlot, true), EditingChoice(target, true))
    }
}

/** Directly resumes an unnamed selection; asks where a new editing change should start. */
class EditChangeAction : DumbAwareAction(
    JujutsuBundle.message("log.action.edit"),
    JujutsuBundle.message("log.action.edit.tooltip"),
    AllIcons.Actions.Edit
) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = editableEntry(e.logEntry) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = editableEntry(e.logEntry) ?: return
        val wc = project.stateModel.workingCopies.value[target.repo.directory.path]
        val choices = editingChoices(target, wc)
        val choice = if (target.description.actual.isBlank()) {
            choices.single()
        } else {
            val labels = choices.map {
                JujutsuBundle.message(
                    if (it.createChild) "log.action.edit.create.child" else "log.action.edit.continue",
                    it.entry.id.short
                )
            } + JujutsuBundle.message("button.cancel")
            val selected = Messages.showDialog(
                project,
                JujutsuBundle.message("log.action.edit.choose.message"),
                JujutsuBundle.message("log.action.edit.choose.title"),
                labels.toTypedArray(),
                0,
                Messages.getQuestionIcon()
            )
            choices.getOrNull(selected) ?: return
        }
        target.repo.createCommand(switchesWorkingCopy = true) {
            if (choice.createChild) new(Description.EMPTY, listOf(choice.entry.id)) else edit(choice.entry.id)
        }.onSuccess {
            invalidate(select = WorkingCopy, vfsChanged = true)
        }.onFailure {
            tellUser("log.action.edit.error")
        }
            .executeAsync()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
