package com.narzaru.mercurial.changes

import com.narzaru.mercurial.diff.HgDiffTabManager
import com.narzaru.mercurial.diff.NavigationHistory
import com.narzaru.mercurial.hg.HgCommandRunner
import com.narzaru.mercurial.hg.HgContentCache
import com.narzaru.mercurial.hg.HgDiffStatParser
import com.narzaru.mercurial.hg.HgFailure
import com.narzaru.mercurial.model.ChangesetsFragments
import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.hg.HgSettings
import com.narzaru.mercurial.hg.HgSettingsConfigurable
import com.narzaru.mercurial.history.HgFileHistoryService
import com.narzaru.mercurial.model.HgDiffStat
import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgListMode
import com.narzaru.mercurial.status.HgFileStatusService
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent as EditorDocumentEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Pair
import com.intellij.ui.JBSplitter
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.ClickListener
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import com.intellij.util.Alarm
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.ToolTipManager
import javax.swing.event.DocumentEvent
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableColumn
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class HgChangesPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val settings = ChangesSettings(project)
    private val review = ReviewState(settings)

    private var displayMode = HgDisplayMode.UNCOMMITTED
    private var listMode = HgListMode.FILES
    private var showUntracked = false
    private var showUnchanged = false

    private var lastActivatedPath = ""

    @Volatile
    private var comparison = ComparisonState()

    private val partialDiff = ConcurrentHashMap<String, Int>()

    private var branchRevisions: List<HgRevision> = emptyList()
    private var revisionSelection = RevisionSelection.DEFAULT
    private var currentBranchName = ""
    private var revisionsVisible = true

    private var filtersVisible = false
    private var statsVisible = true

    private var lastComparison = ""
    private var activeTasks = 0
    private val busy: Boolean get() = activeTasks > 0
    private var toolbar: ActionToolbar? = null
    private val sourceFiles = ArrayList<HgFileItem>()

    private var currentTodoItems: List<HgFileItem> = emptyList()

    private val debounceAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
    private val fileChangeAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)

    private val pendingChangedPaths: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    private var todoRescanPending = false

    private val revisionsAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)

    private val selectionAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)

    private var prefetchId = 0

    private val baseContentCache = HgContentCache()

    private val lineComparator = object : LineComparator {
        override fun changedLines(left: String, right: String): List<DiffFragment> =
            compareLines(left, right, ComparisonPolicy.DEFAULT)

        override fun fragmentLines(left: String, right: String): List<DiffFragment> =
            compareLines(left, right, fragmentPolicy())
    }

    private var refreshId = 0

    private var todoScanId = 0

    private var statsRequestId = 0
    private var statsPending = false

    private val filterField = JBTextField()
    private val excludeField = JBTextField()
    private val branchField = JBTextField(10)
    private val modeCombo = JComboBox(HgDisplayMode.entries.toTypedArray())
    private val branchLabel = JBLabel(" ")
    private val summaryLabel = JBLabel(" ")
    private val filtersRow = JPanel(BorderLayout(4, 0))
    private val modeRow = JPanel(BorderLayout(4, 0))
    private val revisionsBar = RevisionsBar(::toggleRevision, ::resetRevisions)

    private val revisionsSplitter = JBSplitter(true, REVISIONS_PROPORTION_KEY, DEFAULT_TREE_PROPORTION)

    private val branchHolder = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))

    private val truncatedNodes: MutableSet<DefaultMutableTreeNode> =
        Collections.newSetFromMap(WeakHashMap())

    private val statsRenderer: TableCellRenderer = StatsCellRenderer { nodeAt(it) }
    private val eyeRenderer: TableCellRenderer = ReviewCellRenderer({ nodeAt(it) }, ::allReviewed)

    private var uiReady = false

    private val treeRoot = DefaultMutableTreeNode(DirNode(""))
    private val treeModel = ListTreeTableModelOnColumns(treeRoot, buildColumns())

    private var renderedRoot = treeRoot
    private var renderedFiles: List<HgFileItem> = emptyList()

    private val tree = object : TreeTable(treeModel) {
        override fun getToolTipText(event: MouseEvent): String? = tooltipAt(event)

        private var mouseDriven = false

        override fun processMouseEvent(e: MouseEvent) {
            mouseDriven = true
            try {
                super.processMouseEvent(e)
            } finally {
                mouseDriven = false
            }
        }

        override fun changeSelection(row: Int, column: Int, toggle: Boolean, extend: Boolean) {
            super.changeSelection(row, column, toggle, extend)
            if (!mouseDriven && !toggle && !extend) onKeyboardSelection(row)
        }
    }

    init {
        buildUi()
        loadSettings()
        uiReady = true
        followActiveEditor()
        watchDocumentsForTodos()
        watchFilesForChanges()
        project.service<HgChangesService>().panel = this
        refresh()
    }

    override fun dispose() {
        val service = project.service<HgChangesService>()
        if (service.panel === this) service.panel = null
    }

    private fun activateRow(row: Int) {
        activateItem((nodeAt(row)?.userObject as? FileNode)?.item ?: return)
    }

    private fun activateKey(key: String) {
        if (tree.selectedRowCount > 1) return
        val row = (0 until tree.rowCount).firstOrNull { keyAtRow(it) == key } ?: return
        if (!tree.selectionModel.isSelectedIndex(row)) return
        activateRow(row)
    }

    private fun keyAtRow(row: Int): String? =
        (nodeAt(row)?.userObject as? FileNode)?.item?.let { FileKeys.of(it) }

    private fun activateItem(item: HgFileItem) {
        lastActivatedPath = HgPaths.key(item.path)
        syncFileHistory(item)
        if (item.isTodoItem) return
        diffFile(item)
        if (settings.markReviewedOnOpen && review.set(listOf(item), true)) renderReviewMarks()
        prefetchAround(item)
    }

    private fun visibleFiles(): List<HgFileItem> = (0 until tree.rowCount)
        .mapNotNull { (nodeAt(it)?.userObject as? FileNode)?.item }

    private fun onKeyboardSelection(row: Int) {
        if (!uiReady) return
        selectionAlarm.cancelAllRequests()
        val item = (nodeAt(row)?.userObject as? FileNode)?.item ?: return
        val key = FileKeys.of(item)
        selectionAlarm.addRequest({ activateKey(key) }, SELECTION_DEBOUNCE_MS)
    }

    private fun followActiveEditor() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (!settings.selectOpenedFile) return
                    selectOpenedFile(sourcePathOf(event.newFile) ?: return)
                }
            }
        )
    }

    private fun selectCurrentEditorFile() {
        val opened = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        selectOpenedFile(sourcePathOf(opened) ?: return)
    }

    private fun sourcePathOf(file: VirtualFile?): String? {
        if (file == null || file.isDirectory) return null
        if (file.isInLocalFileSystem) return file.path
        return project.service<HgDiffTabManager>().sourcePathOf(file)
    }

    private fun selectOpenedFile(filePath: String) {
        val repoRoot = comparison.repoRoot ?: return
        val key = HgPaths.keyRelativeTo(filePath, repoRoot) ?: return
        for (row in 0 until tree.rowCount) {
            val item = (nodeAt(row)?.userObject as? FileNode)?.item ?: continue
            if (HgPaths.key(item.path) != key) continue
            if (!tree.selectionModel.isSelectedIndex(row) || tree.selectedRowCount > 1) {
                tree.selectionModel.setSelectionInterval(row, row)
            }
            tree.scrollRectToVisible(tree.getCellRect(row, 0, true))
            return
        }
        tree.selectionModel.clearSelection()
    }

    private fun buildUi() {
        val north = JPanel()
        north.layout = BoxLayout(north, BoxLayout.Y_AXIS)
        north.add(buildToolbar())
        north.add(buildModeRow())
        north.add(buildFiltersRow())
        north.add(buildSummaryRow())
        for (i in 0 until north.componentCount) {
            (north.getComponent(i) as? JComponent)?.alignmentX = LEFT_ALIGNMENT
        }

        add(north, BorderLayout.NORTH)
        configureTree()
        val scroll = JBScrollPane(tree)
        scroll.setColumnHeaderView(null)
        revisionsBar.isVisible = false
        revisionsSplitter.firstComponent = scroll
        revisionsSplitter.secondComponent = revisionsBar
        add(revisionsSplitter, BorderLayout.CENTER)

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                north.revalidate()
                north.repaint()
            }
        })
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup()
        group.add(action("Refresh", "Reload the changes", AllIcons.Actions.Refresh) { refresh() })
        group.add(action(
            "Undo Changes", "Revert the selected files", AllIcons.Actions.Rollback,
            { !displayMode.isChangesets }
        ) { revertSelected() })
        group.add(Separator.getInstance())
        group.add(toggle("Files", "List of files", AllIcons.Actions.ListFiles,
            { listMode == HgListMode.FILES }) { setListMode(HgListMode.FILES) })
        group.add(toggle("TODO", "TODO comments in the changed files", AllIcons.General.TodoDefault,
            { listMode == HgListMode.TODO }) { setListMode(HgListMode.TODO) })
        group.add(Separator.getInstance())
        group.add(toggle(
            "Show Untracked", "Show untracked files (?)", HgIcons.EYE_CROSSED,
            { showUntracked },
            { !displayMode.isChangesets }
        ) { setShowUntracked(!showUntracked) })
        group.add(toggle(
            "Show Unchanged",
            "Show files with no real changes (${DiffStatsPlan.UNCHANGED_STATUS})",
            AllIcons.Vcs.Equal,
            { showUnchanged }) { setShowUnchanged(!showUnchanged) })
        group.add(toggle("Filter", "Show the Filter/Exclude fields", AllIcons.General.Filter,
            { filtersVisible }) { setFiltersVisible(!filtersVisible) })
        group.add(Separator.getInstance())
        group.add(toggle(
            "Revisions",
            "Show the branch's revisions under the list and pick which of them the review covers. " +
                "Applies to the '${HgDisplayMode.BRANCH.title}' and 'Changesets' modes.",
            AllIcons.Vcs.Branch,
            { revisionsVisible },
            { displayMode.usesRevisions }
        ) { setRevisionsVisible(!revisionsVisible) })

        val created = ActionManager.getInstance().createActionToolbar("HgChanges", group, true)
        created.targetComponent = this
        created.layoutStrategy = ToolbarLayoutStrategy.WRAP_STRATEGY
        toolbar = created
        return created.component
    }

    private fun buildModeRow(): JPanel {
        modeRow.border = JBUI.Borders.empty(0, 4, 2, 4)
        modeCombo.renderer = object : SimpleListCellRenderer<HgDisplayMode>() {
            override fun customize(
                list: JList<out HgDisplayMode>, value: HgDisplayMode?,
                index: Int, selected: Boolean, hasFocus: Boolean
            ) {
                text = value?.title ?: ""
                toolTipText = value?.hint
            }
        }
        modeCombo.selectedItem = displayMode
        modeCombo.toolTipText = displayMode.hint
        modeCombo.addActionListener {
            val selected = modeCombo.selectedItem as? HgDisplayMode ?: return@addActionListener
            modeCombo.toolTipText = selected.hint
            if (selected != displayMode) setDisplayMode(selected)
            updateBranchFieldVisibility()
        }
        modeRow.add(modeCombo, BorderLayout.CENTER)

        branchHolder.add(branchField)
        modeRow.add(branchHolder, BorderLayout.EAST)
        branchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = triggerDebounce()
        })
        return modeRow
    }

    private fun buildFiltersRow(): JPanel {
        filtersRow.border = JBUI.Borders.empty(0, 4, 2, 4)
        val fields = JPanel(GridLayout(1, 2, 4, 0))
        filterField.emptyText.text = "Filter…"
        excludeField.emptyText.text = "Exclude…"
        fields.add(filterField)
        fields.add(excludeField)
        filtersRow.add(fields, BorderLayout.CENTER)
        for (field in listOf(filterField, excludeField)) {
            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = triggerDebounce()
            })
        }
        filtersRow.isVisible = false
        return filtersRow
    }

    private fun buildSummaryRow(): JPanel {
        val row = JPanel(BorderLayout(8, 0))
        row.border = JBUI.Borders.empty(2, 4, 3, 4)
        branchLabel.componentStyle = UIUtil.ComponentStyle.SMALL
        summaryLabel.componentStyle = UIUtil.ComponentStyle.SMALL
        summaryLabel.horizontalAlignment = SwingConstants.RIGHT
        row.add(branchLabel, BorderLayout.CENTER)
        row.add(summaryLabel, BorderLayout.EAST)
        return row
    }

    private fun action(
        text: String,
        description: String,
        icon: Icon?,
        enabled: () -> Boolean = { true },
        perform: () -> Unit
    ): AnAction =
        object : AnAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = perform()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !busy && enabled()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun toggle(
        text: String,
        description: String,
        icon: Icon?,
        selected: () -> Boolean,
        enabled: () -> Boolean = { true },
        perform: () -> Unit
    ): ToggleAction = object : ToggleAction(text, description, icon) {
        override fun isSelected(e: AnActionEvent) = selected()
        override fun setSelected(e: AnActionEvent, state: Boolean) = perform()

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = enabled()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun buildColumns(): Array<ColumnInfo<*, *>> = arrayOf(
        object : ColumnInfo<DefaultMutableTreeNode, Any>("Files") {
            override fun valueOf(item: DefaultMutableTreeNode): Any = item
            override fun getColumnClass(): Class<*> = TreeTableModel::class.java
        },
        object : ColumnInfo<DefaultMutableTreeNode, String>("±") {
            override fun valueOf(item: DefaultMutableTreeNode): String = ""
            override fun getRenderer(item: DefaultMutableTreeNode?): TableCellRenderer = statsRenderer
        },
        object : ColumnInfo<DefaultMutableTreeNode, String>("") {
            override fun valueOf(item: DefaultMutableTreeNode): String = ""
            override fun getRenderer(item: DefaultMutableTreeNode?): TableCellRenderer = eyeRenderer
        }
    )

    private fun configureTree() {
        tree.setShowGrid(false)
        tree.setTreeCellRenderer(ChangesNodeRenderer(review::isReviewed, ::markTruncated))
        tree.tree.isRootVisible = false
        tree.tree.showsRootHandles = true
        tree.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        tree.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        applyColumnWidths()
        ToolTipManager.sharedInstance().registerComponent(tree)
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                handleClick(event, clickCount)
                return false
            }
        }.installOn(tree)
        installReviewToggleShortcut()
    }

    private fun markTruncated(node: DefaultMutableTreeNode, truncated: Boolean) {
        if (truncated) truncatedNodes.add(node) else truncatedNodes.remove(node)
    }

    private fun applyColumnWidths() {
        column(COL_STATS)?.apply {
            minWidth = STATS_WIDTH; maxWidth = STATS_WIDTH; preferredWidth = STATS_WIDTH; resizable = false
            cellRenderer = statsRenderer
        }
        column(COL_EYE)?.apply {
            minWidth = EYE_WIDTH; maxWidth = EYE_WIDTH; preferredWidth = EYE_WIDTH; resizable = false
            cellRenderer = eyeRenderer
        }
        applyStatsColumnVisibility()
    }

    private fun column(modelIndex: Int): TableColumn? =
        (0 until tree.columnModel.columnCount)
            .map { tree.columnModel.getColumn(it) }
            .firstOrNull { it.modelIndex == modelIndex }

    private fun applyStatsColumnVisibility() {
        val existing = column(COL_STATS)
        when {
            statsVisible && existing == null -> {
                val restored = TableColumn(COL_STATS, STATS_WIDTH, statsRenderer, null).apply {
                    minWidth = STATS_WIDTH; maxWidth = STATS_WIDTH; resizable = false
                }
                tree.columnModel.addColumn(restored)
                tree.columnModel.moveColumn(tree.columnModel.columnCount - 1, COL_STATS)
            }
            !statsVisible && existing != null -> tree.columnModel.removeColumn(existing)
        }
    }

    private fun setStatsVisible(visible: Boolean) {
        if (statsVisible == visible) return
        statsVisible = visible
        settings.statsColumnVisible = visible
        applyStatsColumnVisibility()
        tree.repaint()
    }

    fun installGearActions(toolWindow: ToolWindow) {
        if (toolWindow !is ToolWindowEx) return
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup(
                action("Clear Reviewed", "Drop every review mark", null) { clearReviewed() },
                Separator.getInstance(),
                toggle("Show ± Column", "Show the added/removed lines column", null,
                    { statsVisible }) { setStatsVisible(!statsVisible) },
                toggle(
                    "Open Diff Instead of File",
                    "Arriving at a changed file by a navigation shows its diff instead of the file",
                    null,
                    { settings.openDiffInsteadOfFile }
                ) {
                    settings.openDiffInsteadOfFile = !settings.openDiffInsteadOfFile
                },
                toggle("Mark Reviewed on Open", "Mark a file reviewed when it is opened", null,
                    { settings.markReviewedOnOpen }) {
                    settings.markReviewedOnOpen = !settings.markReviewedOnOpen
                },
                toggle(
                    "Always Select Opened File",
                    "Select the row of the file opened in the editor (no diff, no review mark)",
                    null,
                    { settings.selectOpenedFile }
                ) {
                    settings.selectOpenedFile = !settings.selectOpenedFile
                    if (settings.selectOpenedFile) selectCurrentEditorFile()
                },
                action("Mercurial Port Settings…", "Diff, encoding and editor tab options", null) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, HgSettingsConfigurable::class.java)
                    refresh()
                }
            )
        )
    }

    private fun nodeAt(viewRow: Int): DefaultMutableTreeNode? {
        if (viewRow < 0) return null
        return tree.tree.getPathForRow(viewRow)?.lastPathComponent as? DefaultMutableTreeNode
    }

    private fun allReviewed(node: DefaultMutableTreeNode): Boolean = when (val payload = node.userObject) {
        is FileNode -> review.isReviewed(payload.item)
        is DirNode -> payload.fileCount > 0 && payload.reviewedCount == payload.fileCount
        else -> false
    }

    private fun tooltipAt(event: MouseEvent): String? {
        val viewColumn = tree.columnAtPoint(event.point)
        if (viewColumn < 0) return null
        val modelColumn = tree.convertColumnIndexToModel(viewColumn)
        if (modelColumn == COL_EYE) return ReviewCellRenderer.TOOLTIP
        if (modelColumn != COL_TREE) return null

        val node = nodeAt(tree.rowAtPoint(event.point)) ?: return null
        val payload = node.userObject
        if (node !in truncatedNodes) return null
        return when (payload) {
            is DirNode -> payload.name
            is FileNode -> payload.item.path
            else -> null
        }
    }

    private fun selectedNodes(): List<DefaultMutableTreeNode> =
        tree.selectedRows.toList().mapNotNull { nodeAt(it) }

    private fun handleClick(e: MouseEvent, clickCount: Int) {
        if (e.button != MouseEvent.BUTTON1) return
        val viewRow = tree.rowAtPoint(e.point)
        val node = nodeAt(viewRow) ?: return

        val clickedColumn = tree.columnAtPoint(e.point)
        if (clickedColumn >= 0 && tree.convertColumnIndexToModel(clickedColumn) == COL_EYE) {
            if (clickCount == 1 && review.toggle(ChangesTreeBuilder.filesOf(node))) renderReviewMarks()
            return
        }

        val payload = node.userObject
        if (payload is DirNode) {
            val onTreeColumn = clickedColumn >= 0 && tree.convertColumnIndexToModel(clickedColumn) == COL_TREE
            if (clickCount == 2 && !onTreeColumn) toggleExpand(viewRow)
            return
        }
        val item = (payload as? FileNode)?.item ?: return
        selectionAlarm.cancelAllRequests()
        val activated = HgPaths.key(item.path) == lastActivatedPath
        when (ClickGesture.onFile(clickCount, e.modifiersEx, activated)) {
            FileClickAction.IGNORE -> return
            FileClickAction.ACTIVATE -> activateRow(viewRow)
            FileClickAction.ACTIVATE_AFTER_GUARD -> {
                val key = FileKeys.of(item)
                selectionAlarm.addRequest({ activateKey(key) }, DOUBLE_CLICK_GUARD_MS)
            }
            FileClickAction.OPEN_FILE -> openFile(item)
        }
    }

    private fun syncFileHistory(item: HgFileItem) {
        val repoRoot = comparison.repoRoot ?: return
        project.service<HgFileHistoryService>().syncTo(File(repoRoot, item.path).path)
    }

    private fun toggleExpand(viewRow: Int) {
        val path: TreePath = tree.tree.getPathForRow(viewRow) ?: return
        if (tree.tree.isExpanded(path)) tree.tree.collapsePath(path) else tree.tree.expandPath(path)
    }

    private fun installReviewToggleShortcut() {
        val key = "mercurial.toggleReviewed"
        tree.inputMap.put(KeyStroke.getKeyStroke("SPACE"), key)
        tree.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke("SPACE"), key)
        tree.actionMap.put(key, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = toggleReviewedForSelection()
        })
    }

    private fun loadSettings() {
        filterField.text = settings.filter
        excludeField.text = settings.exclude
        branchField.text = settings.compareBranch
        filtersVisible = settings.filtersVisible
        applyFiltersVisibility()
        showUntracked = settings.showUntracked
        showUnchanged = settings.showUnchanged
        revisionsVisible = settings.revisionsVisible
        statsVisible = settings.statsColumnVisible
        applyStatsColumnVisibility()
        review.reload()
        updateBranchFieldVisibility()
    }

    private fun saveSettings() {
        settings.filter = filterField.text ?: ""
        settings.exclude = excludeField.text ?: ""
        settings.compareBranch = branchField.text ?: ""
    }

    private fun setFiltersVisible(visible: Boolean) {
        filtersVisible = visible
        settings.filtersVisible = visible
        applyFiltersVisibility()
    }

    private fun applyFiltersVisibility() {
        filtersRow.isVisible = filtersVisible
        revalidate()
        repaint()
    }

    private fun setShowUntracked(visible: Boolean) {
        showUntracked = visible
        settings.showUntracked = visible
        refresh()
    }

    private fun setShowUnchanged(visible: Boolean) {
        showUnchanged = visible
        settings.showUnchanged = visible
        renderFiltered()
    }

    private fun setRevisionsVisible(visible: Boolean) {
        revisionsVisible = visible
        settings.revisionsVisible = visible
        updateRevisionsBarVisibility()
    }

    private fun updateRevisionsBarVisibility() {
        revisionsBar.isVisible =
            revisionsVisible && displayMode.usesRevisions && branchRevisions.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun toggleRevision(revision: HgRevision, selected: Boolean) {
        revisionSelection = revisionSelection.with(revision, selected)
        settings.setRevisionOverrides(currentBranchName, revisionSelection.toStorage())
        revisionsBar.updateHeader(branchRevisions, revisionSelection)
        scheduleRevisionsRefresh()
    }

    private fun resetRevisions() {
        revisionSelection = RevisionSelection.DEFAULT
        settings.setRevisionOverrides(currentBranchName, emptyList())
        revisionsBar.update(branchRevisions, revisionSelection)
        scheduleRevisionsRefresh()
    }

    private fun scheduleRevisionsRefresh() {
        revisionsAlarm.cancelAllRequests()
        revisionsAlarm.addRequest({ refresh() }, REVISIONS_DEBOUNCE_MS)
    }

    private fun updateBranchFieldVisibility() {
        val custom = displayMode.usesBranchField
        branchField.isVisible = custom
        branchHolder.isVisible = custom
        modeRow.revalidate()
        modeRow.repaint()
    }

    private fun triggerDebounce() {
        debounceAlarm.cancelAllRequests()
        debounceAlarm.addRequest({
            saveSettings()
            renderFiltered()
        }, FILTER_DEBOUNCE_MS)
    }

    private fun toggleReviewedForSelection() {
        val items = selectedNodes()
            .flatMap { ChangesTreeBuilder.filesOf(it) }
            .distinctBy { review.key(it) }
        if (review.toggle(items)) renderReviewMarks()
    }

    private fun clearReviewed() {
        if (review.clear()) renderReviewMarks()
    }

    private fun setDisplayMode(mode: HgDisplayMode) {
        displayMode = mode
        updateRevisionsBarVisibility()
        refresh()
    }

    private fun setListMode(mode: HgListMode) {
        if (listMode == mode) return
        listMode = mode
        fileChangeAlarm.cancelAllRequests()
        renderFiltered()
        if (mode == HgListMode.TODO && !busy) scanTodos()
    }

    private fun projectStartDir(): File? {
        val path = project.basePath ?: project.guessProjectDir()?.path ?: return null
        return File(path)
    }

    private fun refresh() {
        dropPendingScans()
        val start = projectStartDir()
        if (start == null) {
            showStatus("No project directory")
            return
        }
        val customBranch = branchField.text?.trim().orEmpty()
        if (displayMode.usesBranchField && customBranch.isEmpty()) {
            sourceFiles.clear(); renderFiltered()
            showStatus("Error: Branch name cannot be empty.")
            return
        }

        beginTask()
        showStatus("Loading…")
        val mode = displayMode
        val untracked = showUntracked
        val requestId = ++refreshId

        val trace = newTrace(mode)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val repoRoot = trace.timed("find repo root") { HgCommandRunner.findRepoRoot(start) }
                if (repoRoot == null) {
                    onEdt { if (requestId == refreshId) showStatus("No repo found") }
                    return@executeOnPooledThread
                }
                val result = ChangesLoader(
                    HgCommands.of(HgCommandRunner(repoRoot, trace)),
                    jobs,
                    settings::revisionOverrides,
                    LOG::warn
                ).load(mode, customBranch, untracked, trace)

                onEdt {
                    if (requestId != refreshId) return@onEdt
                    comparison = ComparisonState(repoRoot, result.targetRev, mode, result.revsByFile)
                    baseContentCache.clear()
                    partialDiff.clear()
                    lastActivatedPath = ""
                    if (mode.usesRevisions) {
                        currentBranchName = result.branch
                        branchRevisions = result.revisions
                        revisionSelection = result.selection
                        revisionsBar.update(branchRevisions, revisionSelection)
                    }
                    updateRevisionsBarVisibility()
                    sourceFiles.clear()
                    if (result.error != null) {
                        showStatus(result.error)
                    } else {
                        sourceFiles.addAll(result.files)
                        showStatus(result.statusText)
                    }
                    trace.timed("publish statuses") { publishStatuses() }
                    if (listMode == HgListMode.TODO) scanTodos(trace) else renderFiltered(trace)
                    openRequestedDiff()
                    trace.timed("reload open diffs") {
                        val signature = comparisonSignature()
                        if (signature != lastComparison) {
                            lastComparison = signature
                            project.service<HgChangesService>().reloadOpenDiffs()
                        }
                    }
                    if (result.error == null && result.files.isNotEmpty()) {
                        loadDiffStats(repoRoot, result.targetRev, result.statsRanges, result.statsPaths, trace)
                    } else {
                        report(trace)
                    }
                }
            } finally {
                onEdt { endTask() }
            }
        }
    }

    private fun dropPendingScans() {
        statsRequestId++
        statsPending = false
        todoScanId++
        todoRescanPending = false
        pendingChangedPaths.clear()
        fileChangeAlarm.cancelAllRequests()
    }

    private fun loadDiffStats(
        repoRoot: File,
        targetRev: String,
        ranges: List<DiffRange>,
        paths: Map<DiffRange, List<String>>,
        trace: LoadTrace? = null
    ) {
        val requestId = ++statsRequestId
        statsPending = true
        updateSummary(shownFiles())
        val statJobs = ranges.ifEmpty { listOf(DiffRange(targetRev, "")) }
        val ignoreEolChanges = HgSettings.ignoreEolChanges

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val runner = HgCommandRunner(repoRoot, trace)
                val stats = LinkedHashMap<DiffRange, Map<String, HgDiffStat>>()
                trace.timed("diff stats") {
                    jobs.map(statJobs) { range ->
                        val res = runner.runToBytesDetailed(
                            DiffStatsPlan.arguments(range, paths[range].orEmpty(), ignoreEolChanges)
                        )
                        if (res.exitCode == 0) {
                            range to HgDiffStatParser.parse(res.stdout)
                        } else {
                            LOG.warn("Diff stats are unavailable: ${HgFailure.message(res.failedToStart, res.stderr)}")
                            range to emptyMap()
                        }
                    }.forEach { (range, byPath) -> stats[range] = byPath }
                }
                onEdt {
                    if (requestId != statsRequestId) return@onEdt
                    statsPending = false
                    trace.timed("apply stats") {
                        val counted = DiffStatsPlan.applyStats(sourceFiles, stats, targetRev)
                        sourceFiles.clear()
                        sourceFiles.addAll(counted)
                        publishStatuses()
                    }
                    if (listMode == HgListMode.FILES) {
                        renderFiltered(trace, reuseTree = true)
                    } else {
                        updateSummary(shownFiles())
                    }
                    report(trace)
                }
            } finally {
                onEdt {
                    if (requestId == statsRequestId && statsPending) {
                        statsPending = false
                        updateSummary(shownFiles())
                    }
                }
            }
        }
    }

    private fun publishStatuses() {
        comparison = comparison.withItems(sourceFiles)
        val repoRoot = comparison.repoRoot ?: return
        project.service<HgFileStatusService>().update(repoRoot, sourceFiles)
    }

    private fun shownFiles(): List<HgFileItem> {
        val filter = PathFilter(filterField.text.orEmpty(), excludeField.text.orEmpty())
        return (if (listMode == HgListMode.TODO) currentTodoItems else sourceFiles)
            .filter { (showUnchanged || !it.isUnchanged) && filter.accepts(it.path) }
    }

    private fun renderReviewMarks() = renderFiltered(reuseTree = true)

    private fun renderFiltered(trace: LoadTrace? = null, reuseTree: Boolean = false) {
        val items = trace.timed("filter files") { shownFiles() }
        if (reuseTree && TreeRefreshPlan.keepsStructure(renderedFiles, items)) {
            renderedFiles = items
            trace.timed("refresh tree") {
                ChangesTreeBuilder.refresh(renderedRoot, items, review::isReviewed)
                tree.repaint()
                updateSummary(items)
            }
            return
        }
        val selected = selectedFileKeys()
        val collapsed = collapsedDirs()
        val newRoot = trace.timed("build tree") { ChangesTreeBuilder.build(items, review::isReviewed) }
        trace.timed("show tree") {
            renderedFiles = items
            renderedRoot = newRoot
            treeModel.setRoot(newRoot)
            applyColumnWidths()
            restoreExpansion(collapsed)
            restoreSelection(selected)
            updateSummary(items)
        }
    }

    private fun collapsedDirs(): Set<String>? {
        if (renderedRoot.childCount == 0) return null
        val collapsed = HashSet<String>()
        for (row in 0 until tree.tree.rowCount) {
            val path = tree.tree.getPathForRow(row) ?: continue
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
            if (node.userObject !is DirNode) continue
            if (!tree.tree.isExpanded(path)) collapsed.add(TreeRefreshPlan.dirPath(node))
        }
        return collapsed
    }

    private fun restoreExpansion(collapsed: Set<String>?) {
        if (collapsed == null) {
            TreeUtil.expandAll(tree.tree)
            return
        }
        expandDirs(renderedRoot, TreePath(renderedRoot), collapsed)
    }

    private fun expandDirs(node: DefaultMutableTreeNode, path: TreePath, collapsed: Set<String>) {
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index) as DefaultMutableTreeNode
            if (child.userObject !is DirNode) continue
            if (!TreeRefreshPlan.expandsAfterRebuild(TreeRefreshPlan.dirPath(child), collapsed)) continue
            val childPath = path.pathByAddingChild(child)
            tree.tree.expandPath(childPath)
            expandDirs(child, childPath, collapsed)
        }
    }

    private fun selectedFileKeys(): Set<String> = tree.selectedRows.toList()
        .mapNotNull { (nodeAt(it)?.userObject as? FileNode)?.item }
        .map { HgPaths.key(it.path) }
        .toSet()

    private fun restoreSelection(keys: Set<String>) {
        if (keys.isEmpty()) return
        tree.selectionModel.clearSelection()
        for (row in 0 until tree.rowCount) {
            val item = (nodeAt(row)?.userObject as? FileNode)?.item ?: continue
            if (HgPaths.key(item.path) in keys) tree.selectionModel.addSelectionInterval(row, row)
        }
    }

    private fun updateSummary(items: List<HgFileItem>) {
        val files = items.distinctBy { review.key(it) }
        summaryLabel.text =
            StatusTextFormatter.summary(files, files.count { review.isReviewed(it) }, statsPending)
    }

    private fun showStatus(text: String) {
        branchLabel.text = text.replace('\n', ' ')
        branchLabel.toolTipText = text
    }

    private fun watchDocumentsForTodos() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: EditorDocumentEvent) {
                    if (listMode != HgListMode.TODO) return
                    val repoRoot = comparison.repoRoot ?: return
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (!TodoScan.covers(sourceFiles, repoRoot.absolutePath, file.path)) return
                    todoRescanPending = true
                    scheduleFileChangeReaction()
                }
            },
            this
        )
    }

    private fun watchFilesForChanges() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val paths = changedPathsOf(events)
                    if (paths.isEmpty()) return
                    pendingChangedPaths.addAll(paths)
                    onEdt { scheduleFileChangeReaction() }
                }
            }
        )
    }

    private fun changedPathsOf(events: List<VFileEvent>): List<String> = events.flatMap { event ->
        when (event) {
            is VFileContentChangeEvent, is VFileDeleteEvent,
            is VFileCreateEvent, is VFileCopyEvent -> listOf(event.path)
            is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
            is VFilePropertyChangeEvent ->
                if (event.propertyName == VirtualFile.PROP_NAME) listOf(event.oldPath, event.newPath)
                else emptyList()
            else -> emptyList()
        }
    }

    private fun scheduleFileChangeReaction() {
        fileChangeAlarm.cancelAllRequests()
        fileChangeAlarm.addRequest({ reactToChangedFiles() }, FILE_CHANGE_DELAY_MS)
    }

    private fun reactToChangedFiles() {
        val paths = takePendingChangedPaths()
        val rescanRequested = todoRescanPending
        todoRescanPending = false
        val repoRoot = comparison.repoRoot
        val reaction = if (repoRoot == null) FileChangeReaction.NONE else FileChangeScope.of(
            repoRoot.absolutePath,
            paths,
            sourceFiles,
            project.service<HgDiffTabManager>().openChangesKeys()
        )
        if (reaction.diffKeys.isNotEmpty()) {
            project.service<HgDiffTabManager>().reloadChangesTabs(reaction.diffKeys)
        }
        if (!rescanRequested && !reaction.rescanTodos) return
        if (!busy && listMode == HgListMode.TODO) scanTodos()
    }

    private fun takePendingChangedPaths(): List<String> = synchronized(pendingChangedPaths) {
        val paths = ArrayList(pendingChangedPaths)
        pendingChangedPaths.clear()
        paths
    }

    private fun scanTodos(trace: LoadTrace? = null) {
        val repoRoot = comparison.repoRoot ?: return
        val sources = ArrayList(sourceFiles)
        val requestId = ++todoScanId
        ApplicationManager.getApplication().executeOnPooledThread {
            val todoItems = ArrayList<HgFileItem>()
            trace.timed("scan todos") {
                for (source in sources) {
                    val text = readFileText(File(repoRoot, source.path))
                    todoItems.addAll(TodoParser.parse(source, text))
                }
            }
            onEdt {
                if (requestId != todoScanId || listMode != HgListMode.TODO) return@onEdt
                if (!TodoScan.changed(currentTodoItems, todoItems)) return@onEdt
                currentTodoItems = todoItems
                renderFiltered(trace)
            }
        }
    }

    private fun readFileText(fullPath: File): String {
        val vf = LocalFileSystem.getInstance().findFileByIoFile(fullPath)
        if (vf != null) {
            val doc = inReadAction { FileDocumentManager.getInstance().getDocument(vf) }
            if (doc != null) {
                return inReadAction { doc.text }
            }
        }
        return try {
            if (fullPath.isFile) fullPath.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun openFile(item: HgFileItem) {
        val repoRoot = comparison.repoRoot ?: return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(repoRoot, item.path)) ?: return
        project.service<HgChangesService>().openAsFile(vf.path)
        val line = CaretTransfer.fileTarget(null, item.lineNumber)
        NavigationHistory.record(project) {
            if (line != null) {
                OpenFileDescriptor(project, vf, line, 0).navigate(true)
            } else {
                FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }
        if (settings.markReviewedOnOpen && review.set(listOf(item), true)) renderReviewMarks()
    }

    private fun openRequestedDiff() {
        val service = project.service<HgChangesService>()
        val requested = service.takeRequestedDiff() ?: return
        val repoRoot = comparison.repoRoot ?: return
        val key = HgPaths.keyRelativeTo(requested.path, repoRoot) ?: return
        val item = comparison.changedItemAt(key) ?: return
        selectOpenedFile(requested.path)
        NavigationHistory.record(project) {
            service.openDiffTab(
                File(repoRoot, item.path).path,
                key,
                null,
                requested.caretLine,
                requestFocus = true
            )
        }
    }

    fun reloadForSettings(changesetsOnly: Boolean) {
        if (changesetsOnly && !displayMode.isChangesets) return
        onEdt { refresh() }
    }

    fun isLoaded(): Boolean = comparison.repoRoot != null

    fun hasDiffFor(file: VirtualFile): Boolean = itemFor(file) != null

    fun diffTabKeyOf(file: VirtualFile): String? = itemFor(file)?.let { HgPaths.key(it.path) }

    fun buildDiffRequestForPath(path: String): SimpleDiffRequest? {
        val state = comparison
        val repoRoot = state.repoRoot ?: return null
        val key = HgPaths.keyRelativeTo(path, repoRoot) ?: return null
        val item = state.changedItemAt(key) ?: return null
        val localFile = File(repoRoot, item.path)
        val baseRev = item.baseRev.ifEmpty { state.baseRev }
        return buildRequest(item, localFile, baseRev, computeSides(repoRoot, item, baseRev))
    }

    private fun itemFor(file: VirtualFile): HgFileItem? {
        if (file.isDirectory || !file.isInLocalFileSystem) return null
        val state = comparison
        val repoRoot = state.repoRoot ?: return null
        val key = HgPaths.keyRelativeTo(file.path, repoRoot) ?: return null
        return state.changedItemAt(key)
    }

    private class Sides(
        val base: String?,
        val head: String?,
        val onlyLast: Boolean = false,
        val skippedHunks: Int = 0
    )

    private fun computeSides(repoRoot: File, item: HgFileItem, baseRev: String): Sides {
        val plan = DiffSidesPlan.of(item, baseRev)
        val builder = baseBuilder(repoRoot, comparison)
        var base = if (plan.needsBase) builder.readRevision(plan.baseRev, plan.basePath) else null
        var head: String? = null
        var onlyLast = false
        if (plan.rightIsRevision) {
            head = builder.readRevision(plan.headRev, plan.headPath)
            val before = if (plan.needsBase) branchBase(builder, item, head) else null
            if (plan.needsBase && before == null) {
                onlyLast = true
                base = builder.readRevision(plan.lastChangeBaseRev, plan.headPath)
            } else {
                base = before
            }
        }
        return Sides(base, head, onlyLast, partialDiff[HgPaths.key(item.path)] ?: 0)
    }

    private fun baseBuilder(repoRoot: File, state: ComparisonState): BranchBaseBuilder = BranchBaseBuilder(
        HgCommands.of(HgCommandRunner(repoRoot)),
        baseContentCache,
        state,
        FragmentSettings(
            HgSettings.widenToFragments,
            HgSettings.changesetsFragments == ChangesetsFragments.HG,
            changesetsStamp()
        ),
        lineComparator,
        LOG::warn
    )

    private fun branchBase(builder: BranchBaseBuilder, item: HgFileItem, head: String?): String? {
        val base = builder.read(item, head)
        val fileKey = HgPaths.key(item.path)
        base.skippedHunks?.let {
            if (it > 0) partialDiff[fileKey] = it else partialDiff.remove(fileKey)
        }
        return base.text
    }

    private fun diffFile(item: HgFileItem) {
        val repoRoot = comparison.repoRoot ?: return
        val localFile = File(repoRoot, item.path)
        NavigationHistory.record(project) {
            project.service<HgChangesService>()
                .openDiffTab(localFile.path, HgPaths.key(item.path), tree)
        }
    }

    private fun compareLines(left: String, right: String, policy: ComparisonPolicy): List<DiffFragment> =
        ComparisonManager.getInstance()
            .compareLines(left, right, policy, DumbProgressIndicator.INSTANCE)
            .map {
                DiffFragment(
                    LineRange(it.startLine1, it.endLine1),
                    LineRange(it.startLine2, it.endLine2)
                )
            }

    private fun fragmentPolicy(): ComparisonPolicy = when (HgSettings.changesetsFragments) {
        ChangesetsFragments.PLATFORM_TRIMMED -> ComparisonPolicy.TRIM_WHITESPACES
        ChangesetsFragments.PLATFORM_SETTINGS -> viewerPolicy()
        else -> ComparisonPolicy.DEFAULT
    }

    private fun comparisonSignature(): String =
        ComparisonSignature.of(sourceFiles, comparison.mode, comparison.baseRev, changesetsStamp())

    private fun changesetsStamp(): String = ComparisonSignature.fragmentsStamp(
        HgSettings.widenToFragments, HgSettings.changesetsFragments, fragmentPolicy().name
    )

    private fun viewerPolicy(): ComparisonPolicy =
        TextDiffSettingsHolder.TextDiffSettings.getSettings().ignorePolicy.comparisonPolicy

    private fun prefetchAround(activated: HgFileItem) {
        val state = comparison
        val repoRoot = state.repoRoot ?: return

        val items = PrefetchPlan.around(visibleFiles(), FileKeys.of(activated), FileKeys::of)
            .filterNot { it.isTodoItem }
        if (items.isEmpty()) return

        val requestId = ++prefetchId
        ApplicationManager.getApplication().executeOnPooledThread {
            val builder = baseBuilder(repoRoot, state)
            for (item in items) {
                if (requestId != prefetchId) return@executeOnPooledThread
                val plan = DiffSidesPlan.of(item, state.baseRev)
                if (!plan.rightIsRevision) {
                    if (plan.needsBase) builder.readRevision(plan.baseRev, plan.basePath)
                    continue
                }
                val head = builder.readRevision(plan.headRev, plan.headPath)
                if (plan.needsBase) branchBase(builder, item, head)
            }
        }
    }

    private fun buildRequest(
        item: HgFileItem,
        localFile: File,
        panelBaseRev: String,
        sides: Sides
    ): SimpleDiffRequest? {
        val base = sides.base
        val head = sides.head
        val onlyLast = sides.onlyLast
        val skippedHunks = sides.skippedHunks
        val factory = DiffContentFactory.getInstance()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(localFile.name)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(localFile)
        val plan = DiffSidesPlan.of(item, panelBaseRev)
        val baseRev = if (onlyLast) plan.lastChangeBaseRev else panelBaseRev
        val fromRevision = plan.rightIsRevision

        if (base == null && head == null && vf == null) return null

        val rightIsLocalFile = if (fromRevision) vf != null && head != null && localMatches(vf, head)
        else vf != null
        val baseContent = if (base != null) revisionContent(base, vf, fileType) else factory.createEmpty()
        val rightContent = when {
            fromRevision -> when {
                head == null -> factory.createEmpty()
                rightIsLocalFile -> factory.create(project, vf!!)
                else -> revisionContent(head, vf, fileType)
            }
            vf != null -> factory.create(project, vf)
            else -> factory.createEmpty()
        }
        val baseTitle = DiffTitles.left(base != null, fromRevision, onlyLast, baseRev, item.copiedFrom)
        val rightTitle = DiffTitles.right(
            fromRevision, onlyLast, head != null, rightIsLocalFile, vf != null,
            item.headRev, skippedHunks
        )

        val request = SimpleDiffRequest(localFile.name, baseContent, rightContent, baseTitle, rightTitle)
        request.putUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE, Side.RIGHT)
        project.service<HgChangesService>().takePendingLine(localFile.path)?.let { line ->
            request.putUserData(
                DiffUserDataKeys.SCROLL_TO_LINE,
                Pair.create(Side.RIGHT, line)
            )
        }
        return request
    }

    private fun localMatches(file: VirtualFile, text: String): Boolean {
        val document = inReadAction { FileDocumentManager.getInstance().getDocument(file) }
        if (document == null) {
            LOG.info("Hg diff: no document for ${file.path}, right side falls back to the revision")
            return false
        }
        val local = inReadAction { document.text }
        if (local == text) return true
        LOG.info(
            "Hg diff: ${file.path} differs from the revision, right side falls back to it. " +
                TextMismatch.describe(local, text)
        )
        return false
    }

    private fun revisionContent(text: String, file: VirtualFile?, fileType: FileType) =
        if (file != null) DiffContentFactory.getInstance().create(project, text, file)
        else DiffContentFactory.getInstance().create(project, text, fileType)

    private fun revertSelected() {
        val state = comparison
        val repoRoot = state.repoRoot ?: return
        val selected = selectedNodes()
            .flatMap { ChangesTreeBuilder.filesOf(it) }
            .distinctBy { it.path }
        if (selected.isEmpty()) return
        if (!confirmRevert(selected)) return

        beginTask()
        val mode = state.mode
        val baseRev = state.baseRev
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val runner = HgCommandRunner(repoRoot)
                val result = runner.runToText(RevertPlan.arguments(selected, mode, baseRev))

                val ioFiles = RevertPlan.affectedPaths(selected).map { File(repoRoot, it) }
                onEdt {
                    LocalFileSystem.getInstance().refreshIoFiles(ioFiles)
                    if (!result.success) {
                        val details = result.stderr.ifBlank { "exit code ${result.exitCode}" }
                        Messages.showErrorDialog(
                            project,
                            "Revert failed. ${HgFailure.message(result.failedToStart, details)}",
                            "Error"
                        )
                    } else {
                        refresh()
                    }
                }
            } finally {
                onEdt { endTask() }
            }
        }
    }

    private fun confirmRevert(selected: List<HgFileItem>): Boolean = Messages.showYesNoDialog(
        project,
        RevertPrompt.text(selected, comparison.mode, comparison.baseRev),
        "Confirm revert",
        Messages.getWarningIcon()
    ) == Messages.YES

    private fun onEdt(task: () -> Unit) = ApplicationManager.getApplication().invokeLater(task)

    private fun newTrace(mode: HgDisplayMode): LoadTrace? =
        if (LOG.isDebugEnabled) LoadTrace("${mode.title} load") else null

    private fun report(trace: LoadTrace?) {
        if (trace != null && LOG.isDebugEnabled) LOG.debug(trace.summary())
    }

    private fun <T> LoadTrace?.timed(name: String, work: () -> T): T =
        if (this == null) work() else phase(name, work)

    private fun beginTask() {
        activeTasks++
        toolbar?.updateActionsAsync()
    }

    private fun endTask() {
        if (activeTasks > 0) activeTasks--
        toolbar?.updateActionsAsync()
    }

    private companion object {

        val LOG = Logger.getInstance(HgChangesPanel::class.java)

        val jobs = ParallelJobs(PooledTaskLauncher)

        const val REVISIONS_PROPORTION_KEY = "mercurial.revisionsProportion"
        const val DEFAULT_TREE_PROPORTION = 0.75f

        const val FILTER_DEBOUNCE_MS = 500

        const val SELECTION_DEBOUNCE_MS = 250

        const val DOUBLE_CLICK_GUARD_MS = 300

        const val REVISIONS_DEBOUNCE_MS = 300

        const val FILE_CHANGE_DELAY_MS = 400

        const val COL_TREE = 0
        const val COL_STATS = 1
        const val COL_EYE = 2

        const val STATS_WIDTH = 78
        const val EYE_WIDTH = 26
    }
}

private fun <T> inReadAction(compute: () -> T): T =
    ApplicationManager.getApplication().runReadAction(Computable(compute))
