package `in`.kkkev.jjidea.vcs.merge

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.openapi.progress.EmptyProgressIndicator

/**
 * Applies only the merge tool's [before] → [after] edits to [parent]. Existing working-copy
 * edits ([parent] → [before]) remain excluded. Returns null when those edits touch a merge
 * edit, including insertion boundaries: line alignment cannot safely separate them there.
 * Strings are copied verbatim, preserving line endings and the presence of a final newline.
 */
internal fun isolateResolution(parent: String, before: String, after: String): String? {
    if (before == after) return parent
    if (parent == before) return after

    val comparison = ComparisonManager.getInstance()
    val indicator = EmptyProgressIndicator()
    val existing = try {
        comparison.compareLines(parent, before, ComparisonPolicy.DEFAULT, indicator)
    } catch (_: DiffTooBigException) {
        return null
    }
    val resolution = try {
        comparison.compareLines(before, after, ComparisonPolicy.DEFAULT, indicator)
    } catch (_: DiffTooBigException) {
        return null
    }

    val result = StringBuilder(parent)
    for (edit in resolution.asReversed()) {
        if (existing.any { edit.startOffset1 <= it.endOffset2 && it.startOffset2 <= edit.endOffset1 }) {
            return null
        }
        val offset = existing.filter { it.endOffset2 < edit.startOffset1 }.sumOf {
            (it.endOffset1 - it.startOffset1) - (it.endOffset2 - it.startOffset2)
        }
        result.replace(
            edit.startOffset1 + offset,
            edit.endOffset1 + offset,
            after.substring(edit.startOffset2, edit.endOffset2)
        )
    }
    return result.toString()
}
