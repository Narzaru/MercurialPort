package com.narzaru.mercurial.history

import com.narzaru.mercurial.diff.HgDiffTabManager
import com.narzaru.mercurial.hg.HgCommandRunner
import com.narzaru.mercurial.hg.HgFailure
import com.narzaru.mercurial.hg.HgLogParser
import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.hg.HgRenameParser
import com.narzaru.mercurial.model.HgHistoryItem
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.MouseInputAdapter
import javax.swing.table.DefaultTableCellRenderer

class HgFileHistoryPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private class HistoryTarget(val file: File, val root: File?)

    @Volatile
    private var target: HistoryTarget? = null

    private var followSuppressed = false

    private val titleLabel = JBLabel(HistoryStatusText.NO_FILE_SELECTED)
    private val statusLabel = JBLabel(HistoryStatusText.READY)
    private val tableModel = HistoryTableModel()
    private val table = object : JBTable(tableModel) {
        override fun getToolTipText(event: MouseEvent): String? = tooltipAt(event)

        override fun getToolTipLocation(event: MouseEvent): Point? {
            if (tooltipAt(event) == null) return null
            val row = rowAtPoint(event.point)
            if (row < 0) return null
            val cell = getCellRect(row, 0, true)
            return Point(0, cell.y + cell.height)
        }
    }

    private var diffRequestId = 0

    private var historyRequestId = 0

    private val renameCache = ConcurrentHashMap<String, String>()

    private val followAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        buildUi()
        followActiveEditor()
        currentEditorPath()?.let { loadHistory(it) }
    }

    override fun dispose() {
        val service = project.service<HgFileHistoryService>()
        if (service.panel === this) service.panel = null
        renameCache.clear()
    }

    private fun currentEditorPath(): String? =
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            ?.takeIf { !it.isDirectory }
            ?.let { sourcePathOf(it) }

    private fun sourcePathOf(file: VirtualFile): String? =
        if (file.isInLocalFileSystem) file.path
        else project.service<HgDiffTabManager>().sourcePathOf(file)

    private fun followActiveEditor() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile?.takeIf { !it.isDirectory } ?: return
                    val path = sourcePathOf(file) ?: return
                    requestHistory(path, fromEditor = true)
                }
            }
        )
    }

    fun syncTo(path: String) = requestHistory(path, fromEditor = false)

    private fun requestHistory(path: String, fromEditor: Boolean) {
        if (fromEditor && followSuppressed) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow?.isVisible != true) return
        val normalized = File(path).absolutePath
        if (target?.file?.absolutePath.equals(normalized, ignoreCase = true)) return
        if (!fromEditor) {
            loadHistory(path)
            return
        }
        followAlarm.cancelAllRequests()
        followAlarm.addRequest({ loadHistory(path) }, FOLLOW_DEBOUNCE_MS)
    }

    private fun buildUi() {
        val north = JPanel()
        north.layout = BoxLayout(north, BoxLayout.Y_AXIS)
        north.add(buildToolbar())
        north.add(buildInfoRow())
        for (i in 0 until north.componentCount) {
            (north.getComponent(i) as? JComponent)?.alignmentX = LEFT_ALIGNMENT
        }
        add(north, BorderLayout.NORTH)

        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.addMouseListener(object : MouseInputAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                if (table.rowAtPoint(e.point) < 0) return
                if (e.clickCount == 1) diffSelected()
            }
        })
        configureDateColumn()
        table.setExpandableItemsEnabled(false)
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    private fun tooltipAt(event: MouseEvent): String? {
        val viewRow = table.rowAtPoint(event.point)
        val viewColumn = table.columnAtPoint(event.point)
        if (viewRow < 0 || viewColumn < 0) return null
        val modelColumn = table.convertColumnIndexToModel(viewColumn)

        val raw = tableModel.getValueAt(table.convertRowIndexToModel(viewRow), modelColumn).toString()
        if (modelColumn == HistoryTableModel.COL_DATE) return tooltipFor(HistoryDateText.full(raw).orEmpty())
        if (!isTruncated(raw, viewColumn)) return null
        return tooltipFor(raw)
    }

    private fun tooltipFor(text: String): String? =
        HistoryTooltipText.wrapped(text, JBUI.scale(TOOLTIP_WIDTH), textWidth(text))

    private fun isTruncated(text: String, viewColumn: Int): Boolean =
        textWidth(text) > table.columnModel.getColumn(viewColumn).width - JBUI.scale(CELL_PADDING)

    private fun textWidth(text: String): Int = table.getFontMetrics(table.font).stringWidth(text)

    private fun configureDateColumn() {
        val column = table.columnModel.getColumn(HistoryTableModel.COL_DATE)
        column.cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val raw = value?.toString().orEmpty()
                super.getTableCellRendererComponent(
                    table, HistoryDateText.day(raw), isSelected, hasFocus, row, column
                )
                return this
            }
        }
        column.preferredWidth = JBUI.scale(DATE_COLUMN_WIDTH)
        column.maxWidth = JBUI.scale(DATE_COLUMN_MAX_WIDTH)
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup()
        group.add(action("Refresh", "Reload the file history", AllIcons.Actions.Refresh,
            { target != null }) { reload() })
        group.add(Separator.getInstance())
        group.add(action(
            "Show Diff",
            "Diff the selected revisions as one change: from the parent of the oldest to the newest",
            AllIcons.Actions.Diff, { table.selectedRowCount > 0 }
        ) { diffSelected() })
        group.add(action("Open", "Open the selected revision of the file as a temporary copy",
            AllIcons.Actions.OpenNewTab, { table.selectedRowCount > 0 }) { openSelected() })

        val toolbar = ActionManager.getInstance().createActionToolbar("HgFileHistory", group, true)
        toolbar.targetComponent = this
        toolbar.layoutStrategy = ToolbarLayoutStrategy.WRAP_STRATEGY
        return toolbar.component
    }

    private fun buildInfoRow(): JPanel {
        val row = JPanel(BorderLayout(8, 0))
        row.border = JBUI.Borders.empty(2, 4, 3, 4)
        titleLabel.componentStyle = UIUtil.ComponentStyle.SMALL
        statusLabel.componentStyle = UIUtil.ComponentStyle.SMALL
        statusLabel.horizontalAlignment = SwingConstants.RIGHT
        row.add(titleLabel, BorderLayout.CENTER)
        row.add(statusLabel, BorderLayout.EAST)
        return row
    }

    private fun action(
        text: String,
        description: String,
        icon: Icon?,
        enabled: () -> Boolean,
        perform: () -> Unit
    ): AnAction = object : AnAction(text, description, icon) {
        override fun actionPerformed(e: AnActionEvent) = perform()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun loadHistory(path: String) {
        val file = File(path)
        target = HistoryTarget(file, null)
        titleLabel.text = HistoryStatusText.title(file.name)
        reload()
    }

    private fun reload() {
        val file = target?.file ?: return
        statusLabel.text = HistoryStatusText.LOADING
        val requestId = ++historyRequestId
        ApplicationManager.getApplication().executeOnPooledThread {
            val root = HgCommandRunner.findRepoRoot(file.parentFile)
            if (root == null) {
                onEdt {
                    if (requestId != historyRequestId) return@onEdt
                    statusLabel.text = HistoryStatusText.NO_REPOSITORY
                    tableModel.setItems(emptyList())
                }
                return@executeOnPooledThread
            }
            val rel = HgPaths.relativize(file, root)
            val runner = HgCommandRunner(root)
            val res = runner.run("log", "-f", rel, "--template", "${HgLogParser.TEMPLATE}\n")
            val items = if (res.success) HgLogParser.parse(res.stdout, rel) else emptyList()
            onEdt {
                if (requestId != historyRequestId) return@onEdt
                target = HistoryTarget(file, root)
                if (!res.success && items.isEmpty()) {
                    tableModel.setItems(emptyList())
                    statusLabel.text = HistoryStatusText.hgError(res.stderr, res.failedToStart)
                } else {
                    tableModel.setItems(items)
                    statusLabel.text = HistoryStatusText.loaded(items.size)
                }
            }
        }
    }

    private fun selectedModelRows(): List<Int> =
        table.selectedRows.map { table.convertRowIndexToModel(it) }

    private fun openSelected() {
        val current = target ?: return
        val root = current.root ?: return
        val item = selectedModelRows().firstNotNullOfOrNull { tableModel.itemAt(it) } ?: return
        val rel = item.path.ifBlank { HgPaths.relativize(current.file, root) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val tmp = extractFile(root, rel, item, "open") ?: return@executeOnPooledThread
            onEdt {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp) ?: return@onEdt
                withoutFollow { FileEditorManager.getInstance(project).openFile(vf, true) }
            }
        }
    }

    private fun diffSelected() {
        val current = target ?: return
        val root = current.root ?: return
        val file = current.file
        val items = tableModel.items()
        val revisions = items.map { it.revision }
        val selection = HistoryDiffSelection.of(items, selectedModelRows()) ?: return

        val requestId = ++diffRequestId
        ApplicationManager.getApplication().executeOnPooledThread {
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
            var catError: String? = null
            val trail = HistoryRenameTrail(
                revisionsBelow = { fromRow -> revisions.take(fromRow + 1).reversed() },
                renameSourceOf = { revision, path -> renameSource(root, revision, path) }
            )

            val right = trail.follow(selection.newest.path, selection.newestRow) { path ->
                catText(root, path, selection.newest.revision) { catError = it }
            }
            val left =
                if (!selection.hasParent) HistoryRenameTrail.Found(null, right.path)
                else trail.follow(selection.oldest.path, selection.oldestRow) { path ->
                    catText(root, path, selection.parentRevision) { catError = it }
                }

            val leftLabel = selection.leftLabel(left.path, right.path)
            val rightLabel = selection.rightLabel()
            val key = selection.tabKey(right.path)
            val error = catError

            onEdt {
                if (requestId != diffRequestId) return@onEdt
                try {
                    showDiff(file, fileType, left.text, right.text, leftLabel, rightLabel, key, error)
                } catch (e: Exception) {
                    LOG.warn("Could not open the diff ($key)", e)
                    statusLabel.text = HistoryStatusText.diffError(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun showDiff(
        file: File, fileType: FileType,
        leftContent: String?, rightContent: String?,
        leftLabel: String, rightLabel: String, key: String, catError: String?
    ) {
        val factory = DiffContentFactory.getInstance()
        if (leftContent == null && rightContent == null) {
            statusLabel.text = HistoryStatusText.nothingToCompare(catError)
            return
        }

        val vf = LocalFileSystem.getInstance().findFileByIoFile(file)
        val left = revisionContent(factory, leftContent.orEmpty(), vf, fileType)
        val right = revisionContent(factory, rightContent.orEmpty(), vf, fileType)
        val titles = HistoryStatusText.diffTitles(
            leftLabel, rightLabel,
            hasLeftContent = leftContent != null,
            hasRightContent = rightContent != null
        )
        statusLabel.text = titles.status

        val request = SimpleDiffRequest(file.name, left, right, titles.left, titles.right)
        project.service<HgDiffTabManager>()
            .show(key, file.name, request, table, file.path)
    }

    private fun revisionContent(
        factory: DiffContentFactory,
        text: String,
        file: VirtualFile?,
        fileType: FileType
    ) = if (file != null) factory.create(project, text, file) else factory.create(project, text, fileType)

    private fun renameSource(root: File, rev: String, path: String): String? {
        val key = "$rev|${HgPaths.key(path)}"
        renameCache[key]?.let { return it.ifEmpty { null } }
        val res = HgCommandRunner(root).run("debugrename", "-r", rev, path)
        val source = if (res.success) HgRenameParser.sourceOf(res.stdout) else null
        renameCache[key] = source.orEmpty()
        return source
    }

    private fun catText(root: File, rel: String, rev: String, onError: (String) -> Unit): String? {
        val res = HgCommandRunner(root).runToText(listOf("cat", "-r", rev, rel))
        if (res.success) return res.stdout
        val error = res.stderr.trim().ifBlank { "hg cat -r $rev $rel: exit ${res.exitCode}" }
        onError(error)
        LOG.warn("hg cat -r $rev $rel failed: $error")
        return null
    }

    private fun extractFile(root: File, rel: String, item: HgHistoryItem, prefix: String): File? {
        val tmp = File(
            System.getProperty("java.io.tmpdir"),
            HistoryTempFileName.of(rel, prefix, item.revision, item.author)
        )
        val res = HgCommandRunner(root).runToBytesDetailed(listOf("cat", "-r", item.revision, rel))
        if (res.exitCode != 0) {
            LOG.warn("hg cat -r ${item.revision} $rel failed: ${HgFailure.message(res.failedToStart, res.stderr)}")
            return null
        }
        return try {
            tmp.writeBytes(res.stdout)
            tmp
        } catch (_: Exception) {
            null
        }
    }

    private fun onEdt(task: () -> Unit) = ApplicationManager.getApplication().invokeLater(task)

    fun withoutFollow(block: () -> Unit) {
        followSuppressed = true
        try {
            block()
        } finally {
            onEdt { followSuppressed = false }
        }
    }

    private companion object {
        const val TOOL_WINDOW_ID = "Hg File History"

        const val FOLLOW_DEBOUNCE_MS = 300

        const val DATE_COLUMN_WIDTH = 80
        const val DATE_COLUMN_MAX_WIDTH = 110

        const val TOOLTIP_WIDTH = 420

        const val CELL_PADDING = 8

        val LOG = Logger.getInstance(HgFileHistoryPanel::class.java)
    }
}
