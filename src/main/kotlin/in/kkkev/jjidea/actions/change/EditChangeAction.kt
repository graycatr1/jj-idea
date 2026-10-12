package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
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

/**
 * The log-selected entry that "Edit" can act on, or `null` if editing isn't applicable:
 * immutable changes are excluded because the editing workflow squashes back into the parent.
 */
fun editableEntry(entry: LogEntry?): LogEntry? = entry?.takeIf { !it.immutable }

/** Unnamed changes are editing slots, including slots that already contain file changes. */
internal fun editingSlots(target: LogEntry, children: List<LogEntry>): List<LogEntry> {
    if (target.description.actual.isBlank()) return listOf(target)
    return children.filter {
        it.repo == target.repo &&
            !it.immutable &&
            it.description.actual.isBlank() &&
            it.parentIds == listOf(target.id)
    }
}

/**
 * Toolbar/context-menu "Edit" action that reads its target from the event's log selection.
 * Resumes an unnamed child of the selected change, or creates one if no editing slot exists.
 * An unnamed selection is itself an editing slot and is edited directly.
 *
 * Reads the current selection at action time via [in.kkkev.jjidea.actions.logEntry] (rather
 * than capturing a fixed [LogEntry] at construction), so it can be registered once and
 * referenced by ID from the toolbar, menus, and keyboard shortcuts.
 */
class EditChangeAction : DumbAwareAction(
    JujutsuBundle.message("log.action.edit"),
    JujutsuBundle.message("log.action.edit.tooltip"),
    AllIcons.Actions.Edit
) {
    private val log = Logger.getInstance(javaClass)

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = editableEntry(e.logEntry) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (e.project == null) return
        val target = editableEntry(e.logEntry) ?: return

        fun resume(slot: LogEntry?) {
            target.repo.createCommand {
                if (slot != null) edit(slot.id) else new(Description.EMPTY, listOf(target.id))
            }
                .onSuccess {
                    invalidate(select = WorkingCopy, vfsChanged = true)
                    log.info("Editing ${slot?.id ?: "new child of ${target.id}"}")
                }.onFailure { tellUser("log.action.edit.error") }
                .executeAsync()
        }

        fun findSlot() {
            target.repo.runRecoverableInBackground(retry = ::findSlot) {
                val children = if (target.description.actual.isBlank()) {
                    emptyList()
                } else {
                    target.repo.logService.getLogBasic(Expression("children(${target.id.full})")).getOrThrow()
                }
                val slots = editingSlots(target, children)
                runLater {
                    val current = slots.singleOrNull { it.isWorkingCopy }
                    when {
                        current != null -> resume(current)
                        slots.size <= 1 -> resume(slots.singleOrNull())
                        else -> JBPopupFactory.getInstance().createPopupChooserBuilder(slots)
                            .setTitle(JujutsuBundle.message("log.action.edit.choose.slot"))
                            .setRenderer(
                                SimpleListCellRenderer.create<LogEntry> { label, slot, _ ->
                                    label.text = slot.id.short
                                }
                            )
                            .setItemChosenCallback { resume(it) }
                            .createPopup()
                            .showInBestPositionFor(e.dataContext)
                    }
                }
            }
        }
        findSlot()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
