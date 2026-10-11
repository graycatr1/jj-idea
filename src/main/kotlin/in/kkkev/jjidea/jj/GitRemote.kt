package `in`.kkkev.jjidea.jj

/** A git remote: a named repository with a URL. */
data class GitRemote(val name: String, val url: String)

/** Parses `jj git remote list` output (`<name> <url>` per line); lines without a URL are skipped. */
fun parseGitRemotes(stdout: String): List<GitRemote> = stdout.lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .mapNotNull { line ->
        val name = line.substringBefore(' ')
        val url = line.substringAfter(' ', "").trim()
        if (url.isEmpty()) null else GitRemote(name, url)
    }
