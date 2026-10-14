package `in`.kkkev.jjidea.vcs.merge

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.diffedit.DiffEditTool
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.conflict.JjMarkerConflictExtractor
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.relativePathOf
import `in`.kkkev.jjidea.jj.runRecoverableInBackground
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.filePath
import java.nio.file.Files

/** A merge's original parent and file trees, captured before opening the merge dialog. */
internal data class ResolutionReturn(
    val repo: JujutsuRepository,
    val source: LogEntry,
    val parent: LogEntry,
    val file: VirtualFile,
    val before: String,
    val parentContent: String
) {
    companion object {
        /** Only an unnamed direct child has an unambiguous original change to return to. */
        fun capture(repo: JujutsuRepository, file: VirtualFile): ResolutionReturn? {
            val entries = repo.logService.getLogBasic(Expression("@ | parents(@)")).getOrThrow()
            val source = entries.singleOrNull { it.isWorkingCopy } ?: return null
            if (source.description.actual.isNotBlank() || source.parentIds.size != 1) return null
            val parent = entries.singleOrNull { it.id == source.parentIds.single() } ?: return null
            if (!parent.hasConflict || parent.immutable) return null
            val parentContent = when (val result = repo.commandExecutor.show(file.filePath, parent.commitId)) {
                is CommandExecutor.CommandResult.Success -> result.stdout
                is CommandExecutor.CommandResult.Failure -> return null
            }
            if (JjMarkerConflictExtractor().extract(parentContent.toByteArray(Charsets.UTF_8)) == null) return null
            return ResolutionReturn(repo, source, parent, file, Files.readString(file.toNioPath()), parentContent)
        }
    }
}

internal data class SelectedResolution(
    val context: ResolutionReturn,
    val content: String,
    val after: String,
    val deleted: Boolean
)

private data class CompletedResolution(val context: ResolutionReturn, val bytes: ByteArray, val deleted: Boolean)

/** One offer per merge batch: returning one file must not invalidate offers for the remaining files. */
private fun offerResolutionReturn(completed: List<CompletedResolution>) {
    if (completed.isEmpty()) return
    val first = completed.first().context
    val repo = first.repo
    fun prepare() {
        repo.runRecoverableInBackground(retry = ::prepare) {
            val selected = completed.mapNotNull { (context, bytes, deleted) ->
                val after = bytes.toString(Charsets.UTF_8)
                val content = if (deleted) {
                    if (context.before == context.parentContent) "" else null
                } else {
                    isolateResolution(context.parentContent, context.before, after)
                }
                content?.let { SelectedResolution(context, it, after, deleted) }
            }
            runLater {
                val excluded = completed.filter { done -> selected.none { it.context == done.context } }
                val message = buildString {
                    if (selected.isNotEmpty()) {
                        append(
                            JujutsuBundle.message(
                                "merge.return.offer",
                                selected.joinToString {
                                    it.context.file.name
                                },
                                first.parent.id.short
                            )
                        )
                    }
                    if (excluded.isNotEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append(
                            JujutsuBundle.message(
                                "merge.return.overlap",
                                excluded.joinToString {
                                    it.context.file.name
                                },
                                first.parent.id.short
                            )
                        )
                    }
                }
                val notification = NotificationGroupManager.getInstance().getNotificationGroup("Jujutsu")
                    .createNotification(
                        JujutsuBundle.message("merge.return.title"),
                        message,
                        NotificationType.INFORMATION
                    )
                if (selected.isNotEmpty()) {
                    notification.addAction(
                        NotificationAction.createSimpleExpiring(
                            JujutsuBundle.message("merge.return.action", first.parent.id.short)
                        ) {
                            repo.createCommand(switchesWorkingCopy = true) {
                                executeResolutionReturn(selected)
                            }.onSuccess {
                                invalidate(select = WorkingCopy, vfsChanged = true)
                                JujutsuNotifications.notify(
                                    project,
                                    JujutsuBundle.message("merge.return.title"),
                                    JujutsuBundle.message(
                                        "merge.return.done",
                                        selected.joinToString {
                                            it.context.file.name
                                        },
                                        first.parent.id.short
                                    ),
                                    NotificationType.INFORMATION
                                )
                            }.onFailure { tellUser("merge.return.failed") }.executeAsync()
                        }
                    )
                }
                notification.notify(repo.project)
            }
        }
    }
    prepare()
}

/** Validate the snapshot and stage only selected resolution deltas, preserving the editing slot. */
internal fun executeResolutionReturn(selected: List<SelectedResolution>): CommandExecutor.CommandResult {
    if (selected.isEmpty()) {
        return CommandExecutor.CommandResult.Failure.Blocked(
            JujutsuBundle.message("merge.return.stale")
        )
    }
    val first = selected.first().context
    val repo = first.repo
    fun stale() = CommandExecutor.CommandResult.Failure.Blocked(JujutsuBundle.message("merge.return.stale"))
    val entries = repo.logService.getLogBasic(Expression("@ | ${first.parent.id.full}")).getOrElse { return stale() }
    val wc = entries.singleOrNull { it.isWorkingCopy }
    val destination = entries.singleOrNull { it.id == first.parent.id }
    if (wc?.id != first.source.id ||
        wc.parentIds != listOf(first.parent.id) ||
        destination?.commitId != first.parent.commitId ||
        destination.immutable ||
        selected.any {
            it.context.repo != repo ||
                it.context.source.id != first.source.id ||
                it.context.parent.id != first.parent.id ||
                it.context.parent.commitId != first.parent.commitId
        }
    ) {
        return stale()
    }
    for ((context, _, after, deleted) in selected) {
        val path = context.file.toNioPath()
        val unchanged = try {
            if (deleted) !Files.exists(path) else Files.exists(path) && Files.readString(path) == after
        } catch (_: java.io.IOException) {
            false
        }
        if (!unchanged) return stale()
    }
    return try {
        DiffEditTool.withStagingTree(
            selected.filterNot { it.deleted }.associate { repo.relativePathOf(it.context.file) to it.content },
            selected.filter { it.deleted }.map { repo.relativePathOf(it.context.file) }.toSet(),
            preserveParentExecutable = true
        ) { configArgs, tool ->
            repo.commandExecutor.squashIntoInteractive(
                source = first.source.id,
                destination = first.parent.id,
                description = first.parent.description,
                keepEmptied = true,
                configArgs = configArgs,
                tool = tool
            )
        }
    } catch (e: java.io.IOException) {
        CommandExecutor.CommandResult.Failure.Blocked(e.message ?: JujutsuBundle.message("merge.return.stale"))
    }
}

/** Prepare off the EDT; merge dialogs and notifications themselves run on the EDT. */
internal fun openMergeWithResolutionReturn(project: Project, files: List<VirtualFile>, provider: JujutsuMergeProvider) {
    val byRepo = files.groupBy { provider.repositoryFor(it) }
    byRepo.forEach { (repo, repoFiles) ->
        if (repo == null) {
            JujutsuConflictResolver(project, provider).resolve(repoFiles)
        } else {
            fun prepare() {
                repo.runRecoverableInBackground(retry = ::prepare) {
                    val returns = repoFiles.associateWith { ResolutionReturn.capture(repo, it) }
                    runLater {
                        // If files changed while preparation was running, resolve their live state
                        // normally, but do not offer a delta captured from another starting tree.
                        val valid = returns.filter { (file, context) ->
                            context != null && Files.readString(file.toNioPath()) == context.before
                        }
                        val completed = mutableListOf<CompletedResolution>()
                        JujutsuConflictResolver(project, provider, onResolved = { file, bytes, deleted ->
                            valid[file]?.let {
                                // CommitMessage Documents use LF, but saving preserves a file's
                                // line separator. Isolate and validate the actual saved result.
                                val saved = if (deleted) bytes else Files.readAllBytes(file.toNioPath())
                                completed += CompletedResolution(it, saved, deleted)
                            }
                        }).resolve(repoFiles)
                        offerResolutionReturn(completed)
                    }
                }
            }
            prepare()
        }
    }
}
