package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.actions.uniqueRepo
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate

/** At this many entries, abandoning is always confirmed, even if every change is empty, undescribed and unbookmarked. */
private const val BULK_CONFIRM_THRESHOLD = 10

/**
 * Abandon change action for a single entry (e.g. the working copy panel); see [abandonChangesAction].
 */
fun abandonChangeAction(project: Project, entry: LogEntry?) = abandonChangesAction(project, listOfNotNull(entry))

/**
 * The entries that can be abandoned together, or `null` if they can't: the list must be non-empty, every
 * entry mutable, and all in one repository (a single `jj abandon` runs in one repo).
 */
internal fun abandonableEntries(entries: List<LogEntry>): List<LogEntry>? =
    entries.takeIf { it.isNotEmpty() && it.none { e -> e.immutable } && it.uniqueRepo != null }

/**
 * Where to select after abandoning [targets]: the working copy if it is among them (jj creates a new empty
 * one), otherwise the first parent of any target that is not itself being abandoned, else the working copy.
 */
internal fun abandonSelectAfter(targets: List<LogEntry>): ChangeId? {
    if (targets.any { it.isWorkingCopy }) return null
    val abandoned = targets.map { it.id }.toSet()
    return targets.asSequence().flatMap { it.parentIds }.firstOrNull { it !in abandoned }
}

/**
 * Abandon change(s) action. Removes the changes from the log in one `jj abandon` (one operation, one undo),
 * with a single confirmation if any has file modifications, a description, or a local bookmark — `jj abandon`
 * deletes local bookmarks pointing at the abandoned revision (jj-idea-940s, GitHub #129), so an empty,
 * undescribed change with a bookmark still needs a warning or the bookmark disappears silently.
 *
 * Disabled unless every entry is mutable and all are in the same repository ([abandonableEntries]).
 */
fun abandonChangesAction(project: Project, entries: List<LogEntry>) = nullAndDumbAwareAction(
    abandonableEntries(entries),
    if (entries.size > 1) "log.action.abandon.plural" else "log.action.abandon",
    AllIcons.General.Delete
) {
    val targets = target

    // Check if confirmation is needed
    abandonConfirmMessage(targets)?.let { confirmMessage ->
        val confirmTitle = if (targets.size == 1) {
            JujutsuBundle.message("log.action.abandon.confirm.title", targets.single().id.short)
        } else {
            JujutsuBundle.message("log.action.abandon.confirm.title.plural", targets.size)
        }

        // If user selected No or cancelled, don't proceed
        if (Messages.showYesNoDialog(
                project,
                confirmMessage,
                confirmTitle,
                Messages.getWarningIcon()
            ) != Messages.YES
        ) {
            log.info("User cancelled abandon of ${targets.map { it.id }}")
            return@nullAndDumbAwareAction
        }
    }

    // Select a surviving parent after abandon; if abandoning the WC, jj creates a new one automatically
    val repo = targets.first().repo
    val selectAfter = abandonSelectAfter(targets) ?: WorkingCopy

    repo.createCommand { abandon(targets.map { it.id }) }
        .onSuccess {
            invalidate(select = selectAfter, vfsChanged = true)
            log.info("Abandoned changes ${targets.map { it.id }}")
        }.onFailure { tellUser("log.action.abandon.error") }
        .addUndoTracking("log.action.abandon.undo")
        .executeAsync()
}

/**
 * Builds the abandon-confirmation dialog message for [entry], or `null` if abandoning it is safe
 * without confirmation. See [abandonConfirmMessage] for the list form.
 */
internal fun abandonConfirmMessage(entry: LogEntry): String? = abandonConfirmMessage(listOf(entry))

/**
 * Builds the abandon-confirmation dialog message for [entries], or `null` if abandoning them is safe
 * without confirmation (no file modifications, no description, and no local bookmark that would
 * be deleted along with them). Bulk selections (at least [BULK_CONFIRM_THRESHOLD]) are always confirmed.
 *
 * Only local, non-deleted bookmarks are listed: `jj abandon` deletes local bookmarks pointing at
 * the abandoned revision, but leaves remote-tracking bookmarks (`name@remote`) alone.
 */
internal fun abandonConfirmMessage(entries: List<LogEntry>): String? {
    val single = entries.size == 1
    val localBookmarks = entries.flatMap { it.bookmarks }.filter { !it.isRemote && !it.deleted }.distinct()
    // Abandon is only offered for mutable changes (see the log context menu), whose emptiness is never deferred.
    val withFiles = entries.count { !it.resolveEmpty() }
    val withDescription = entries.count { !it.description.empty }

    val reasons = buildList {
        if (withFiles > 0) {
            add(
                if (single) {
                    JujutsuBundle.message("log.action.abandon.confirm.reason.files")
                } else {
                    JujutsuBundle.message("log.action.abandon.confirm.reason.files.plural", withFiles)
                }
            )
        }
        if (withDescription > 0) {
            add(
                if (single) {
                    JujutsuBundle.message("log.action.abandon.confirm.reason.description")
                } else {
                    JujutsuBundle.message("log.action.abandon.confirm.reason.description.plural", withDescription)
                }
            )
        }
        if (localBookmarks.isNotEmpty()) {
            val names = localBookmarks.joinToString(", ") { it.localName }
            val key = if (localBookmarks.size == 1) {
                "log.action.abandon.confirm.reason.bookmark"
            } else {
                "log.action.abandon.confirm.reason.bookmarks"
            }
            add(JujutsuBundle.message(key, names))
        }
    }

    if (reasons.isEmpty()) {
        return if (entries.size >= BULK_CONFIRM_THRESHOLD) {
            JujutsuBundle.message("log.action.abandon.confirm.bulk", entries.size)
        } else {
            null
        }
    }

    val bulletList = reasons.joinToString("\n") { "• $it" }
    return if (single) {
        JujutsuBundle.message("log.action.abandon.confirm.message", bulletList)
    } else {
        JujutsuBundle.message("log.action.abandon.confirm.message.plural", entries.size, bulletList)
    }
}
