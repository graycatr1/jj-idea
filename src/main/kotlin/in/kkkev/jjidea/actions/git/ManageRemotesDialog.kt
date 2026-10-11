package `in`.kkkev.jjidea.actions.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.GitRemote
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.parseGitRemotes
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel

/**
 * Lists a repository's Git remotes and lets the user add, edit (rename / change URL) and remove them
 * (jj-idea-06tq.2). Each change runs `jj git remote ...` immediately - these are not undoable via
 * `jj op revert` - so the dialog only has a Close button, and the table reloads from jj after each change.
 *
 * In a multi-root project a repository drop-down at the top switches which repo is managed (the remotes
 * of the newly selected repo load in the background).
 *
 * **Important**: [initialRemotes] must be loaded off EDT first (see [showManageRemotes]).
 * Authentication is deliberately not handled here: jj uses the user's Git credential helper / SSH agent.
 */
class ManageRemotesDialog(
    private val project: Project,
    private val repos: List<JujutsuRepository>,
    initialRepo: JujutsuRepository,
    initialRemotes: List<GitRemote>
) : DialogWrapper(project) {
    private var repo = initialRepo
    private val tableModel = RemotesTableModel(initialRemotes)
    private val table = JBTable(tableModel)

    init {
        title = JujutsuBundle.message("dialog.git.remotes.title")
        setOKButtonText(JujutsuBundle.message("dialog.git.remotes.close"))
        init()
    }

    override fun createActions() = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { addRemote() }
            .setEditAction { editRemote() }
            .setRemoveAction { removeRemote() }
            .disableUpDownActions()
            .createPanel()
            .also { it.preferredSize = JBUI.size(560, 220) }
        return panel {
            if (repos.size > 1) {
                row(JujutsuBundle.message("dialog.git.remotes.repository.label")) {
                    comboBox(repos)
                        .applyToComponent {
                            // Replacement (textListCellRenderer) unavailable until 2026.2
                            @Suppress("removal")
                            renderer = SimpleListCellRenderer.create("") { it.displayName }
                            selectedItem = repo
                            addActionListener { (selectedItem as? JujutsuRepository)?.let(::switchRepo) }
                        }
                }
            }
            row { cell(decorated).align(AlignX.FILL) }.resizableRow()
            row { comment(JujutsuBundle.message("dialog.git.remotes.auth.hint")) }
        }
    }

    private fun selectedRemote(): GitRemote? = table.selectedRow.takeIf { it >= 0 }?.let { tableModel.remotes[it] }

    private fun addRemote() {
        val dialog = RemoteEditDialog(project, tableModel.remotes.map { it.name }, null)
        if (!dialog.showAndGet()) return
        val (name, url) = dialog.result ?: return
        runChange(repoStateToo = false) { gitRemoteAdd(name, url) }
    }

    private fun editRemote() {
        val old = selectedRemote() ?: return
        val dialog = RemoteEditDialog(project, tableModel.remotes.map { it.name }.filter { it != old.name }, old)
        if (!dialog.showAndGet()) return
        val (name, url) = dialog.result ?: return
        if (name == old.name && url == old.url) return
        runChange(repoStateToo = name != old.name) { editRemote(old, name, url) }
    }

    private fun removeRemote() {
        val remote = selectedRemote() ?: return
        val confirmed = Messages.showYesNoDialog(
            project,
            JujutsuBundle.message("dialog.git.remotes.remove.message", remote.name, remote.url),
            JujutsuBundle.message("dialog.git.remotes.remove.title"),
            JujutsuBundle.message("dialog.git.remotes.remove.confirm"),
            Messages.getCancelButton(),
            Messages.getWarningIcon()
        ) == Messages.YES
        if (!confirmed) return
        runChange(repoStateToo = true) { gitRemoteRemove(remote.name) }
    }

    /**
     * Runs a remote-changing command, then refreshes the table and every consumer of the remote list.
     * [repoStateToo]: also refresh the log/bookmarks - needed when remote-tracking bookmarks can change
     * (remove, rename), not for add or a URL change.
     */
    private fun runChange(repoStateToo: Boolean, action: CommandExecutor.() -> CommandExecutor.CommandResult) {
        repo.createCommand(action)
            .onSuccess {
                if (repoStateToo) repo.invalidate()
                project.stateModel.gitRemotes.invalidate()
                reload()
            }
            .onFailure { tellUser("dialog.git.remotes.error") }
            .executeAsync()
    }

    private fun switchRepo(newRepo: JujutsuRepository) {
        if (newRepo === repo) return
        repo = newRepo
        tableModel.update(emptyList())
        reload()
    }

    private fun reload() {
        val target = repo
        runInBackground {
            val result = target.commandExecutor.gitRemoteList()
            if (result is CommandExecutor.CommandResult.Success) {
                // Drop the result if the user has since switched to another repo.
                runLater { if (target === repo) tableModel.update(parseGitRemotes(result.stdout)) }
            }
        }
    }

    private class RemotesTableModel(remotes: List<GitRemote>) : AbstractTableModel() {
        var remotes: List<GitRemote> = remotes
            private set

        override fun getRowCount() = remotes.size

        override fun getColumnCount() = 2

        override fun getColumnName(column: Int) = JujutsuBundle.message(
            if (column == 0) "dialog.git.remotes.column.name" else "dialog.git.remotes.column.url"
        )

        override fun getValueAt(row: Int, column: Int) = remotes[row].let { if (column == 0) it.name else it.url }

        override fun isCellEditable(row: Int, column: Int) = false

        fun update(value: List<GitRemote>) {
            remotes = value
            fireTableDataChanged()
        }
    }
}

/** Add/edit form for one remote: name + URL, validated live via [validateRemoteName]/[validateRemoteUrl]. */
internal class RemoteEditDialog(
    project: Project,
    private val otherNames: List<String>,
    private val existing: GitRemote?
) : DialogWrapper(project) {
    private val nameField = JBTextField(existing?.name.orEmpty())
    private val urlField = JBTextField(existing?.url.orEmpty())

    var result: Pair<String, String>? = null
        private set

    init {
        title = JujutsuBundle.message(
            if (existing == null) "dialog.git.remotes.add.title" else "dialog.git.remotes.edit.title"
        )
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(JujutsuBundle.message("dialog.git.remotes.column.name")) {
            cell(nameField).columns(COLUMNS_MEDIUM).focused()
        }
        row(JujutsuBundle.message("dialog.git.remotes.column.url")) {
            cell(urlField).columns(COLUMNS_LARGE)
        }
        row { comment(JujutsuBundle.message("dialog.git.remotes.url.hint")) }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        validateRemoteName(nameField.text.trim(), otherNames)?.let { return ValidationInfo(it, nameField) }
        validateRemoteUrl(urlField.text)?.let { return ValidationInfo(it, urlField) }
        return null
    }

    override fun doOKAction() {
        result = nameField.text.trim() to urlField.text.trim()
        super.doOKAction()
    }
}
