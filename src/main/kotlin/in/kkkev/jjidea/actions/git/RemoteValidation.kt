package `in`.kkkev.jjidea.actions.git

import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.GitRemote

/** Characters git forbids anywhere in a ref name (and so in a remote name), besides whitespace/control chars. */
private const val FORBIDDEN_NAME_CHARS = "~^:?*[\\"

/**
 * Validates a Git remote name for add/rename. Mirrors git's ref-name rules loosely (jj/git will still have
 * the last word and its error is shown verbatim): non-blank, no whitespace/control or `~^:?*[\`, no `..`,
 * `//` or `@{`, not starting with `-` or `.`, not ending with `/`, `.` or `.lock`, and unique among
 * [existingNames]. Returns a user-facing message, or null when valid.
 */
fun validateRemoteName(name: String, existingNames: Collection<String>): String? = when {
    name.isBlank() -> JujutsuBundle.message("dialog.git.remotes.error.name.empty")
    name in existingNames -> JujutsuBundle.message("dialog.git.remotes.error.name.exists", name)
    name.any { it.isWhitespace() || it.isISOControl() || it in FORBIDDEN_NAME_CHARS } ||
        name.startsWith("-") ||
        name.startsWith(".") ||
        name.endsWith("/") ||
        name.endsWith(".") ||
        name.endsWith(".lock") ||
        ".." in name ||
        "//" in name ||
        "@{" in name ->
        JujutsuBundle.message("dialog.git.remotes.error.name.invalid")

    else -> null
}

/**
 * Validates a remote URL. Deliberately permissive - https, ssh, scp-like (`git@host:path`) and local
 * paths are all legitimate, and paths may contain spaces - so only a blank value, or one jj would
 * mistake for an option (leading `-`), is rejected. Returns a user-facing message, or null when valid.
 */
fun validateRemoteUrl(url: String): String? = when {
    url.isBlank() -> JujutsuBundle.message("dialog.git.remotes.error.url.empty")
    url.trim().startsWith("-") -> JujutsuBundle.message("dialog.git.remotes.error.url.invalid")
    else -> null
}

/**
 * Applies an edit of [old] to ([newName], [newUrl]): `set-url` first (under the old name), then `rename`, each
 * only when changed. Stops at and returns the first failure; with no change returns a successful no-op
 * via the final result of whichever step ran last. Callers guarantee at least one field differs.
 */
fun CommandExecutor.editRemote(old: GitRemote, newName: String, newUrl: String): CommandExecutor.CommandResult {
    var result: CommandExecutor.CommandResult? = null
    if (newUrl != old.url) {
        result = gitRemoteSetUrl(old.name, newUrl)
        if (result is CommandExecutor.CommandResult.Failure) return result
    }
    if (newName != old.name) {
        result = gitRemoteRename(old.name, newName)
    }
    return result ?: error("editRemote called with no change")
}
