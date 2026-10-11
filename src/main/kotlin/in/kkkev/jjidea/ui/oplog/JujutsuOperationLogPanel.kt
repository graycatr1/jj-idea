package `in`.kkkev.jjidea.ui.oplog

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.OperationId
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.components.DateTimeFormatter
import `in`.kkkev.jjidea.ui.services.performUndo
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import kotlinx.datetime.Instant
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * The "Operations" tab: a browsable `jj op log` for one repository at a time (jj-idea-aii0),
 * modelled on IntelliJ's Local History. The primary row action is **Revert** (`jj op revert`),
 * which undoes exactly one operation and composes safely with later ones; **Restore** (`jj op
 * restore`) is a reset, so it lives in its own menu section behind a confirmation. See
 * docs/design/undo-support-roadmap.md for why.
 *
 * Refreshes on the state model's `logRefresh` (the same funnel the log uses); [OperationLogLoader]
 * turns a refresh with an unchanged head into a single `-n1` probe.
 */
internal class JujutsuOperationLogPanel(private val project: Project, initialRepo: JujutsuRepository) :
    JPanel(BorderLayout()),
    Disposable {
    private val repos = project.stateModel.initialisedRepositories.value.values.toList()
    private var repo: JujutsuRepository = initialRepo
    private val loaders = mutableMapOf<JujutsuRepository, OperationLogLoader>()

    private var all: List<OperationEntry> = emptyList()
    private var showSnapshots = false
    private var shown: List<OperationEntry> = emptyList()

    private val model = object : AbstractTableModel() {
        private val columns = listOf("description", "command", "time")

        override fun getRowCount() = shown.size

        override fun getColumnCount() = columns.size

        override fun getColumnName(column: Int) = JujutsuBundle.message("oplog.column.${columns[column]}")

        override fun getValueAt(row: Int, column: Int): Any {
            val entry = shown[row]
            return when (column) {
                0 -> entry.title()
                1 -> entry.args.removePrefix("args: ")
                else -> entry.time
            }
        }
    }

    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        emptyText.text = JujutsuBundle.message("oplog.empty")
        // Time is last, formatted exactly like the log's Date column (Today/Yesterday/locale date).
        columnModel.getColumn(TIME_COLUMN).apply {
            cellRenderer = TimeCellRenderer()
            preferredWidth = JBUI.scale(TIME_COLUMN_WIDTH)
            maxWidth = JBUI.scale(TIME_COLUMN_WIDTH * 2)
        }
    }

    private fun selected(): OperationEntry? = table.selectedRow.takeIf { it >= 0 }?.let { shown.getOrNull(it) }

    init {
        add(buildNorth(), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        PopupHandler.installPopupMenu(table, buildRowActions(), "JujutsuOperationLogPopup")
        project.stateModel.logRefresh.connect(this) { refresh() }
        refresh()
    }

    private fun buildNorth(): JPanel {
        val north = JPanel(BorderLayout())
        val group = DefaultActionGroup().apply {
            add(object : DumbAwareAction(
                JujutsuBundle.message("oplog.action.refresh"),
                null,
                AllIcons.Actions.Refresh
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT

                override fun actionPerformed(e: AnActionEvent) = refresh()
            })
            add(object : ToggleAction(
                JujutsuBundle.message("oplog.action.snapshots"),
                null,
                AllIcons.Actions.ToggleVisibility
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT

                override fun isSelected(e: AnActionEvent) = showSnapshots

                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    showSnapshots = state
                    applyFilter()
                }
            })
            add(Separator.getInstance())
            add(buildRowActions())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("JujutsuOperationLogToolbar", group, true)
        toolbar.targetComponent = table
        north.add(toolbar.component, BorderLayout.CENTER)
        if (repos.size > 1) {
            val combo = ComboBox(repos.toTypedArray()).apply {
                selectedItem = repo
                addActionListener {
                    (selectedItem as? JujutsuRepository)?.let {
                        repo = it
                        all = emptyList()
                        applyFilter()
                        refresh()
                    }
                }
            }
            north.add(combo, BorderLayout.EAST)
        }
        north.border = JBUI.Borders.empty()
        return north
    }

    /** Revert (primary), then - separated, labelled as a reset - Restore. Shared by toolbar and popup. */
    private fun buildRowActions() = DefaultActionGroup().apply {
        add(object : DumbAwareAction(
            JujutsuBundle.message("oplog.action.revert"),
            JujutsuBundle.message("oplog.action.revert.description"),
            AllIcons.Actions.Rollback
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) = applyState(e, revertState(selected()))

            override fun actionPerformed(e: AnActionEvent) {
                val entry = selected() ?: return
                performUndo(project, repo, entry.id)
            }
        })
        add(Separator.getInstance())
        add(object : DumbAwareAction(
            JujutsuBundle.message("oplog.action.restore"),
            JujutsuBundle.message("oplog.action.restore.description"),
            null
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) = applyState(e, restoreState(selected(), all.firstOrNull()))

            override fun actionPerformed(e: AnActionEvent) {
                val entry = selected() ?: return
                restore(entry)
            }
        })
    }

    private fun applyState(e: AnActionEvent, state: RowActionState) {
        e.presentation.isEnabled = state.enabled
        state.disabledReason?.let { e.presentation.description = it }
    }

    private fun restore(entry: OperationEntry) {
        val later = all.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
        val choice = Messages.showYesNoDialog(
            project,
            restoreConfirmMessage(entry, later),
            JujutsuBundle.message("oplog.restore.confirm.title"),
            JujutsuBundle.message("oplog.action.restore.confirm"),
            Messages.getCancelButton(),
            Messages.getWarningIcon()
        )
        if (choice != Messages.YES) return
        restoreOperation(repo, entry.id)
    }

    private fun restoreOperation(target: JujutsuRepository, id: OperationId) {
        target.createCommand { opRestore(id) }
            .onSuccess { invalidate(select = WorkingCopy, vfsChanged = true) }
            .onFailure { tellUser("notification.oplog.restore.error") }
            .executeAsync()
    }

    private fun refresh() {
        val target = repo
        val loader = loaders.getOrPut(target) { OperationLogLoader(target.commandExecutor) }
        runInBackground {
            val result = loader.load()
            runLater {
                if (target != repo) return@runLater
                when (result) {
                    is OperationLogLoader.Result.Loaded -> if (result.changed) {
                        all = result.entries
                        applyFilter()
                    }
                    is OperationLogLoader.Result.Failed -> table.emptyText.text = result.message
                }
            }
        }
    }

    /** Re-applies the snapshot filter, keeping the selected operation selected when it is still visible. */
    private fun applyFilter() {
        val keep = selected()?.id
        shown = all.visibleOperations(showSnapshots)
        model.fireTableDataChanged()
        keep?.let { id -> shown.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            ?.let { table.setRowSelectionInterval(it, it) }
    }

    override fun dispose() = Unit

    private class TimeCellRenderer : DefaultTableCellRenderer() {
        override fun setValue(value: Any?) {
            val instant = value as? Instant
            text = instant?.let(DateTimeFormatter::formatRelative).orEmpty()
            toolTipText = instant?.let(DateTimeFormatter::formatAbsolute)
        }
    }

    private companion object {
        const val TIME_COLUMN = 2
        const val TIME_COLUMN_WIDTH = 140
    }
}
