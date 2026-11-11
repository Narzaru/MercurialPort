package com.narzaru.mercurial.changes

import com.narzaru.mercurial.diff.HgDiffTabManager
import com.narzaru.mercurial.diff.NavigationHistory
import com.narzaru.mercurial.hg.HgCommandRunner
import com.narzaru.mercurial.hg.HgContentHash
import com.narzaru.mercurial.hg.HgFailure
import com.narzaru.mercurial.hg.HgOpenBranches
import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.hg.HgResult
import com.narzaru.mercurial.hg.HgSettings
import com.narzaru.mercurial.hg.HgSettingsConfigurable
import com.narzaru.mercurial.history.HgFileHistoryService
import com.narzaru.mercurial.model.ChangesetsFragments
import com.narzaru.mercurial.model.HgChangeRow
import com.narzaru.mercurial.model.HgDiffStat
import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgFileStatus
import com.narzaru.mercurial.model.HgListMode
import com.narzaru.mercurial.model.HgTodoItem
import com.narzaru.mercurial.status.HgFileStatusService
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
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Condition
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
    private val review = ReviewState(ReviewMarks(settings))

    private var displayMode = HgDisplayMode.UNCOMMITTED
    private var listMode = HgListMode.FILES
    private var showUntracked = false
    private var showUnchanged = false

    private var lastActivatedPath = ""

    @Volatile
    var comparison = ComparisonState()
        private set

    val diffSource = ChangesDiffSource(project)

    private var branchRevisions: List<HgRevision> = emptyList()
    private var branchRevisionsMoreThan: Int? = null
    private var revisionsState = RevisionsState()
    private var revisionsVisible = true

    private var filtersVisible = false
    private var statsVisible = true

    private var lastComparison = ""
    private var activeTasks = 0
    private val busy: Boolean get() = activeTasks > 0
    private var toolbar: ActionToolbar? = null
    private val sourceFiles = ArrayList<HgFileItem>()

    private var currentTodoItems: List<HgTodoItem> = emptyList()

    @Volatile
    private var disposed = false

    private val expired = Condition<Any?> { disposed || project.isDisposed }

    private val debounceAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val fileChangeAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val fileChanges = FileChangeCoordinator()

    private val revisionsAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val selectionAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private var refreshId = 0

    private var todoScanId = 0

    private val statsScheduler = StatsScheduler()

    private var reviewStatsIndicator: ProgressIndicator? = null

    private val filterField = JBTextField()
    private val excludeField = JBTextField()
    private val branchField = BranchField(::onBranchTextChanged, ::reloadForBranch, ::fetchBranchChoices)
    private val branchReload = BranchReloadPlan()
    private val branchChoices = BranchChoices()
    private val branchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val modeCombo = JComboBox(HgDisplayMode.entries.toTypedArray())
    private val branchLabel = JBLabel(" ")
    private val summaryLabel = JBLabel(" ")
    private val filtersRow = JPanel(BorderLayout(4, 0))
    private val modeRow = JPanel(BorderLayout(4, 0))
    private val revisionsBar = RevisionsBar(::toggleRevisions, ::resetRevisions)

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
    private var renderedFiles: List<HgChangeRow> = emptyList()

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
        disposed = true
        val service = project.service<HgChangesService>()
        if (service.panel === this) service.panel = null
    }

    private fun activateRow(row: Int) {
        activateItem(itemAt(row) ?: return)
    }

    private fun activateKey(key: String) {
        if (tree.selectedRowCount > 1) return
        val row = rowOf(key) ?: return
        if (!tree.selectionModel.isSelectedIndex(row)) return
        activateRow(row)
    }

    private fun itemAt(row: Int): HgChangeRow? = (nodeAt(row)?.userObject as? FileNode)?.row

    private fun rowOf(fileKey: String): Int? =
        (0 until tree.rowCount).firstOrNull { row -> itemAt(row)?.let { FileKeys.of(it) } == fileKey }

    private fun activateItem(item: HgChangeRow) {
        lastActivatedPath = HgPaths.key(item.file.path)
        syncFileHistory(item.file)
        if (item !is HgFileItem) return
        diffFile(item)
        if (settings.markReviewedOnOpen && review.set(listOf(item), true)) renderReviewMarks()
        prefetchAround(item)
    }

    private fun visibleFiles(): List<HgChangeRow> = (0 until tree.rowCount).mapNotNull { itemAt(it) }

    private fun onKeyboardSelection(row: Int) {
        if (!uiReady) return
        selectionAlarm.cancelAllRequests()
        val item = itemAt(row) ?: return
        val key = FileKeys.of(item)
        selectionAlarm.addRequest({ activateKey(key) }, SELECTION_DEBOUNCE_MS)
    }

    private fun followActiveEditor() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (!settings.selectOpenedFile) return
                    selectOpenedFile(sourcePathOf(event.newFile ?: return) ?: return)
                }
            }
        )
    }

    private fun selectCurrentEditorFile() {
        val opened = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        selectOpenedFile(sourcePathOf(opened) ?: return)
    }

    private fun sourcePathOf(file: VirtualFile): String? = project.service<HgDiffTabManager>().sourcePathOf(file)

    private fun selectOpenedFile(filePath: String) {
        val repoRoot = comparison.repoRoot ?: return
        val key = HgPaths.keyRelativeTo(filePath, repoRoot) ?: return
        val rowKeys = (0 until tree.rowCount).map { row -> itemAt(row)?.let { HgPaths.key(it.file.path) } }
        val target = OpenedFileRow.of(rowKeys, tree.selectedRows.toSet(), key)
        if (target == null) {
            tree.selectionModel.clearSelection()
            return
        }
        if (target.select) tree.selectionModel.setSelectionInterval(target.row, target.row)
        tree.scrollRectToVisible(tree.getCellRect(target.row, 0, true))
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
            "Show files with no real changes (${HgFileStatus.UNCHANGED.letter})",
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
        configureBranchField()
        return modeRow
    }

    private fun configureBranchField() {
        branchField.setMinimumAndPreferredWidth(JBUI.scale(BRANCH_FIELD_WIDTH))
        branchField.toolTipText =
            "Branch or revision to compare against. Type to filter default and the branches not merged into it."
    }

    private fun branchText(): String = branchField.text

    private fun setBranchText(text: String) {
        branchField.text = text
    }

    private fun onBranchTextChanged() {
        triggerDebounce()
        scheduleBranchReload()
    }

    private fun scheduleBranchReload() {
        if (!uiReady || branchAlarm.isDisposed) return
        branchAlarm.cancelAllRequests()
        if (branchReload.reloadsWhileTyping(displayMode.usesBranchField, branchText(), branchChoices.names)) {
            branchAlarm.addRequest({ reloadForBranch() }, BRANCH_RELOAD_DEBOUNCE_MS)
        }
    }

    private fun reloadForBranch() {
        branchAlarm.cancelAllRequests()
        if (!uiReady || !branchReload.needsReload(displayMode.usesBranchField, branchText())) return
        saveSettings()
        refresh()
    }

    private fun fetchBranchChoices() {
        val stamp = branchChoices.startFetch() ?: return
        val loadedRoot = comparison.repoRoot
        val start = projectStartDir()
        ApplicationManager.getApplication().executeOnPooledThread {
            var names: List<String>? = null
            try {
                val repoRoot = loadedRoot ?: HgCommandRunner.findRepoRoot(start)
                if (repoRoot != null) {
                    val result = HgCommandRunner(repoRoot).runToText(HgOpenBranches.ARGUMENTS)
                    if (result.success) names = HgOpenBranches.parse(result.stdout)
                    else LOG.warn("Branches are unavailable: ${HgFailure.message(result)}")
                }
            } finally {
                val fetched = names
                onEdt {
                    branchChoices.fetched(stamp, fetched)
                    if (fetched != null) branchField.setChoices(branchChoices.names)
                }
            }
        }
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
                action("Clear Reviewed", "Drop the review marks of the current branch", null) { clearReviewed() },
                Separator.getInstance(),
                toggle("Show ± Column", "Show the added/removed lines column", null,
                    { statsVisible }) { setStatsVisible(!statsVisible) },
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

    private fun selectedFiles(): List<HgFileItem> = selectedNodes().flatMap { ChangesTreeBuilder.filesOf(it) }

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
        val item = (payload as? FileNode)?.row ?: return
        selectionAlarm.cancelAllRequests()
        val activated = HgPaths.key(item.file.path) == lastActivatedPath
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
        setBranchText(settings.compareBranch)
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
        settings.compareBranch = branchText()
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
        revisionsBar.isVisible = revisionsVisible && displayMode.usesRevisions &&
            (branchRevisions.isNotEmpty() || branchRevisionsMoreThan != null) &&
            RevisionsSync.isBranchKnown(revisionsState.branch)
        revalidate()
        repaint()
    }

    private fun toggleRevisions(picked: List<HgRevision>, selected: Boolean) {
        val selection = RevisionPicking.apply(
            branchRevisions,
            revisionsState.selection,
            picked,
            selected
        )
        revisionsState = revisionsState.copy(selection = selection)
        storeRevisionOverrides(selection.toStorage())
        revisionsBar.applySelection(branchRevisions, selection)
        revisionsBar.updateHeader(branchRevisions, selection)
        scheduleRevisionsRefresh()
    }

    private fun resetRevisions() {
        revisionsState = revisionsState.copy(selection = RevisionSelection.DEFAULT)
        storeRevisionOverrides(emptyList())
        revisionsBar.update(branchRevisions, revisionsState.selection, branchRevisionsMoreThan)
        scheduleRevisionsRefresh()
    }

    private fun dropOverridesOfGoneRevisions(branch: String, revisions: List<HgRevision>) {
        val pruned = RevisionsSync.prunedOverrides(branch, revisions, settings.revisionOverrides(branch)) ?: return
        settings.setRevisionOverrides(branch, pruned)
    }

    private fun storeRevisionOverrides(values: List<String>) {
        val branch = revisionsState.branch
        if (RevisionsSync.isBranchKnown(branch)) settings.setRevisionOverrides(branch, values)
    }

    private fun scheduleRevisionsRefresh() {
        refreshId++
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
        if (review.toggle(selectedFiles().distinctBy { review.key(it) })) renderReviewMarks()
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
        if (fileChanges.workPending) scheduleFileChangeReaction()
    }

    private fun projectStartDir(): File? {
        val path = project.basePath ?: project.guessProjectDir()?.path ?: return null
        return File(path)
    }

    private fun refresh() {
        dropPendingScans()
        val requestId = ++refreshId
        val start = projectStartDir()
        if (start == null) {
            failLoad("No project directory")
            return
        }
        val customBranch = branchText().trim()
        branchReload.loadStarted(displayMode.usesBranchField, customBranch)
        branchAlarm.cancelAllRequests()
        if (displayMode.usesBranchField && customBranch.isEmpty()) {
            failLoad("Error: Branch name cannot be empty.")
            return
        }

        beginTask()
        showStatus("Loading…")
        val mode = displayMode
        val untracked = showUntracked
        val usesReviewEngine = mode.isChangesets && HgSettings.usesReviewEngine

        val trace = newTrace(mode)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val repoRoot = trace.timed("find repo root") { HgCommandRunner.findRepoRoot(start) }
                if (repoRoot == null) {
                    onEdt { if (requestId == refreshId) showStatus("No repo found") }
                    return@executeOnPooledThread
                }
                val runner = HgCommandRunner(repoRoot, trace)
                val result = ChangesLoader(
                    HgCommands.of(runner),
                    jobs,
                    BranchSizeGuard(HgSettings.largestBranch),
                    settings::revisionOverrides,
                    LOG::warn,
                    if (usesReviewEngine) ReviewEngineSetup.create(runner) else null
                ).load(mode, customBranch, untracked, trace)

                onEdt {
                    if (requestId == refreshId) applyLoad(repoRoot, mode, result, trace)
                }
            } finally {
                onEdt {
                    if (requestId == refreshId) project.service<HgChangesService>().takeRequestedDiff()
                    endTask()
                }
            }
        }
    }

    private fun applyLoad(repoRoot: File, mode: HgDisplayMode, result: ChangesResult, trace: LoadTrace?) {
        branchChoices.invalidate()
        startComparison(repoRoot, mode, result)
        if (mode.usesRevisions) applyBranchRevisions(result)
        updateRevisionsBarVisibility()
        showLoadedFiles(result)
        trace.timed("publish statuses") { publishStatuses() }
        if (listMode == HgListMode.TODO) scanTodos(trace) else renderFiltered(trace)
        trace.timed("reload open diffs") { reloadOpenDiffsIfComparisonChanged() }
        openRequestedDiff()
        if (result.error == null && result.files.isNotEmpty()) {
            val load = statsScheduler.startLoad(result.statsRanges, result.statsPaths)
            val engineReview = result.review
            if (engineReview != null) loadReviewStats(engineReview, load, trace)
            else loadDiffStats(repoRoot, result.targetRev, load, trace)
        } else {
            report(trace)
        }
    }

    private fun startComparison(repoRoot: File, mode: HgDisplayMode, result: ChangesResult) {
        comparison = ComparisonState(repoRoot, result.targetRev, mode, result.revsByFile, review = result.review)
        val repoState = RepoState.parse(result.currentInfo)
        fileChanges.loaded(repoState)
        val engine = if (result.review != null) "|${ChangesetsFragments.UPSOURCE.name}" else ""
        review.startLoad(
            repoState.takeIf { it.isKnown }?.branch.orEmpty(),
            "${mode.name}|${result.targetRev}$engine"
        )
        diffSource.invalidate()
        lastActivatedPath = ""
    }

    private fun applyBranchRevisions(result: ChangesResult) {
        revisionsState = RevisionsSync.afterLoad(revisionsState, result.branch, result.selection)
        branchRevisions = result.revisions
        branchRevisionsMoreThan = result.revisionsMoreThan
        revisionsBar.update(branchRevisions, revisionsState.selection, branchRevisionsMoreThan)
        dropOverridesOfGoneRevisions(result.branch, result.revisions)
    }

    private fun showLoadedFiles(result: ChangesResult) {
        sourceFiles.clear()
        if (result.error != null) {
            showStatus(result.error)
        } else {
            sourceFiles.addAll(result.files)
            showStatus(result.statusText)
        }
    }

    private fun reloadOpenDiffsIfComparisonChanged() {
        val signature = comparisonSignature()
        if (signature == lastComparison) return
        lastComparison = signature
        project.service<HgChangesService>().reloadOpenDiffs()
    }

    private fun failLoad(status: String) {
        comparison = ComparisonState()
        sourceFiles.clear()
        currentTodoItems = emptyList()
        renderFiltered()
        showStatus(status)
        project.service<HgChangesService>().takeRequestedDiff()
    }

    private fun dropPendingScans() {
        statsScheduler.reset()
        reviewStatsIndicator?.cancel()
        reviewStatsIndicator = null
        diffSource.cancelPrefetch()
        todoScanId++
        fileChanges.reset()
        fileChangeAlarm.cancelAllRequests()
    }

    private fun loadDiffStats(repoRoot: File, targetRev: String, load: StatsLoad, trace: LoadTrace? = null) {
        updateSummary(shownFiles())
        val ignoreEolChanges = HgSettings.ignoreEolChanges
        val contentPaths = DiffStatsPlan.contentHashedPaths(sourceFiles)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val loader = diffStatsLoader(HgCommandRunner(repoRoot, trace), repoRoot)
                val stats = trace.timed("diff stats") {
                    loader.countAll(targetRev, load.ranges, load.paths, ignoreEolChanges)
                }
                val contentHashes = trace.timed("content hashes") { loader.contentHashes(contentPaths) }
                onEdt {
                    if (!statsScheduler.finishLoad(load)) return@onEdt
                    trace.timed("apply stats") {
                        val counted = DiffStatsPlan.applyStats(sourceFiles, stats, targetRev)
                        sourceFiles.clear()
                        sourceFiles.addAll(counted)
                        review.learn(DiffStatsPlan.diffHashes(counted, stats, targetRev, contentHashes))
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
                    if (statsScheduler.abandonLoad(load)) {
                        review.learn(emptyMap())
                        updateSummary(shownFiles())
                    }
                    if (statsScheduler.isCurrent(load)) flushPendingStatsKeys()
                }
            }
        }
    }

    private fun loadReviewStats(engineReview: ChangesetsReview, load: StatsLoad, trace: LoadTrace?) {
        updateSummary(shownFiles())
        val indicator = EmptyProgressIndicator()
        reviewStatsIndicator = indicator

        ApplicationManager.getApplication().executeOnPooledThread {
            var counted: Map<String, HgDiffStat?>? = null
            try {
                counted = trace.timed("review stats") {
                    ProgressManager.getInstance().runProcess(Computable { engineReview.stats() }, indicator)
                }
            } catch (_: ProcessCanceledException) {
            } finally {
                val stats = counted
                onEdt {
                    if (stats != null && statsScheduler.finishLoad(load)) {
                        trace.timed("apply stats") {
                            val applied = ReviewStats.apply(sourceFiles, stats)
                            sourceFiles.clear()
                            sourceFiles.addAll(applied)
                            review.learn(ReviewStats.hashes(stats))
                            publishStatuses()
                        }
                        if (listMode == HgListMode.FILES) {
                            renderFiltered(trace, reuseTree = true)
                        } else {
                            updateSummary(shownFiles())
                        }
                        report(trace)
                    }
                    if (statsScheduler.abandonLoad(load)) {
                        review.learn(emptyMap())
                        updateSummary(shownFiles())
                    }
                    if (statsScheduler.isCurrent(load)) flushPendingStatsKeys()
                }
            }
        }
    }

    private fun recountStats(keys: Collection<String>) {
        val repoRoot = comparison.repoRoot
        val targetRev = comparison.baseRev
        val command = statsScheduler.recount(keys, repoRoot != null && !busy, refreshId) { batch ->
            PartialStatsPlan.of(comparison.mode, targetRev, sourceFiles, batch)
        }
        if (repoRoot == null) return
        when (command) {
            StatsCommand.Wait -> return
            is StatsCommand.CountAll -> {
                review.suspend(sourceFiles.map { review.key(it) })
                loadDiffStats(repoRoot, targetRev, command.load)
            }
            is StatsCommand.Count -> {
                review.suspend(command.run.action.keys)
                countChangedFiles(repoRoot, targetRev, command.run)
            }
        }
    }

    private fun countChangedFiles(repoRoot: File, targetRev: String, run: PartialStatsRun) {
        val action = run.action
        val ignoreEolChanges = HgSettings.ignoreEolChanges

        ApplicationManager.getApplication().executeOnPooledThread {
            var byPath: Map<String, HgDiffStat>? = null
            var contentHashes: Map<String, String> = emptyMap()
            try {
                val loader = diffStatsLoader(HgCommandRunner(repoRoot), repoRoot)
                byPath = if (action.needsDiff) {
                    loader.count(action.range, action.paths, ignoreEolChanges)
                } else {
                    emptyMap()
                }
                contentHashes = loader.contentHashes(action.contentPaths)
            } finally {
                val stats = byPath
                val hashes = contentHashes
                onEdt {
                    val outcome = statsScheduler.finishPartial(run, refreshId)
                    if (outcome == PartialStatsOutcome.SUPERSEDED) return@onEdt
                    val current = outcome == PartialStatsOutcome.CURRENT
                    if (stats != null && current) {
                        applyPartialStats(mapOf(action.range to stats), hashes, targetRev, action.keys)
                    } else if (current && review.learn(hashes)) {
                        renderReviewMarks()
                    }
                    flushPendingStatsKeys()
                }
            }
        }
    }

    private fun diffStatsLoader(runner: HgCommandRunner, repoRoot: File) = DiffStatsLoader(
        HgDiffCommands.of(runner),
        jobs,
        { path -> HgContentHash.ofFile(File(repoRoot, path)) },
        LOG::warn
    )

    private fun applyPartialStats(
        stats: Map<DiffRange, Map<String, HgDiffStat>>,
        contentHashes: Map<String, String>,
        targetRev: String,
        keys: Set<String>
    ) {
        val counted = DiffStatsPlan.applyStatsToKeys(sourceFiles, stats, targetRev, keys)
        val recountedFiles = counted.filter { review.key(it) in keys }
        val marksMoved = review.learn(DiffStatsPlan.diffHashes(recountedFiles, stats, targetRev, contentHashes))
        val recounted = counted.filterIndexed { index, item -> item != sourceFiles[index] }
        if (recounted.isEmpty()) {
            if (marksMoved) renderReviewMarks()
            return
        }
        sourceFiles.clear()
        sourceFiles.addAll(counted)
        republishStatuses(recounted)
        if (listMode == HgListMode.FILES) {
            renderFiltered(trace = null, reuseTree = true)
        } else {
            updateSummary(shownFiles())
        }
    }

    private fun flushPendingStatsKeys() = recountStats(emptyList())

    private fun publishStatuses() {
        comparison = comparison.withItems(sourceFiles)
        val repoRoot = comparison.repoRoot ?: return
        project.service<HgFileStatusService>().update(repoRoot, sourceFiles)
    }

    private fun republishStatuses(items: List<HgFileItem>) {
        comparison = comparison.withItemsMerged(items)
        val repoRoot = comparison.repoRoot ?: return
        project.service<HgFileStatusService>().merge(repoRoot, items)
    }

    private fun listedFiles(): List<HgChangeRow> = if (listMode == HgListMode.TODO) currentTodoItems else sourceFiles

    private fun shownFiles(): List<HgChangeRow> {
        val filter = PathFilter(filterField.text.orEmpty(), excludeField.text.orEmpty())
        return listedFiles().filter { (showUnchanged || !it.file.isUnchanged) && filter.accepts(it.file.path) }
    }

    private fun renderReviewMarks() = renderFiltered(reuseTree = true)

    private fun renderFiltered(trace: LoadTrace? = null, reuseTree: Boolean = false) {
        val items = trace.timed("filter files") { shownFiles() }
        if (reuseTree && TreeRefreshPlan.keepsStructure(renderedFiles, items)) {
            renderedFiles = items
            trace.timed("refresh tree") {
                ChangesTreeBuilder.refresh(renderedRoot, items, listedFiles(), review::isReviewed)
                tree.repaint()
                updateSummary(items)
            }
            return
        }
        val selected = selectedFileKeys()
        val collapsed = collapsedDirs()
        val newRoot = trace.timed("build tree") { ChangesTreeBuilder.build(items, listedFiles(), review::isReviewed) }
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
        .mapNotNull { itemAt(it) }
        .map { HgPaths.key(it.file.path) }
        .toSet()

    private fun restoreSelection(keys: Set<String>) {
        if (keys.isEmpty()) return
        tree.selectionModel.clearSelection()
        for (row in 0 until tree.rowCount) {
            val item = itemAt(row) ?: continue
            if (HgPaths.key(item.file.path) in keys) tree.selectionModel.addSelectionInterval(row, row)
        }
    }

    private fun updateSummary(items: List<HgChangeRow>) {
        val files = items.map { it.file }.distinctBy { review.key(it) }
        summaryLabel.text =
            StatusTextFormatter.summary(files, files.count { review.isReviewed(it) }, statsScheduler.loading)
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
                    fileChanges.todoRescanRequested()
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
                    fileChanges.pathsChanged(paths)
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

    private fun scheduleFileChangeReaction(delayMs: Int = FILE_CHANGE_DELAY_MS) {
        if (fileChangeAlarm.isDisposed) return
        fileChangeAlarm.cancelAllRequests()
        fileChangeAlarm.addRequest({ reactToChangedFiles() }, delayMs)
    }

    private fun reactToChangedFiles() {
        val paths = fileChanges.takeChangedPaths()
        val repoRoot = comparison.repoRoot
        val reaction = if (repoRoot == null) FileChangeReaction.NONE else FileChangeScope.of(
            repoRoot.absolutePath,
            paths,
            sourceFiles,
            project.service<HgDiffTabManager>().openChangesKeys()
        )
        execute(fileChanges.react(reaction, conditions()))
    }

    private fun conditions() = PanelConditions(busy, comparison.repoRoot != null, listMode == HgListMode.TODO)

    private fun execute(commands: List<FileChangeCommand>) {
        for (command in commands) {
            when (command) {
                is FileChangeCommand.ReloadDiffTabs ->
                    project.service<HgDiffTabManager>().reloadChangesTabs(command.keys)
                FileChangeCommand.Refresh -> refresh()
                is FileChangeCommand.Recount -> recountStats(command.keys)
                FileChangeCommand.RescanTodos -> scanTodos()
                is FileChangeCommand.ProbeRepoState -> probeRepoState(command)
                is FileChangeCommand.AskUnlisted -> askAboutUnlistedPaths(command)
                is FileChangeCommand.ReportUnchecked -> LOG.warn("Unlisted paths are unchecked: ${command.details}")
                FileChangeCommand.RetryLater -> scheduleFileChangeReaction(BUSY_RETRY_DELAY_MS)
            }
        }
    }

    private fun probeRepoState(probe: FileChangeCommand.ProbeRepoState) {
        val repoRoot = comparison.repoRoot ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            var probed = RepoState.UNKNOWN
            try {
                val result = HgCommandRunner(repoRoot).runToText(RepoState.ARGUMENTS)
                if (result.success) probed = RepoState.parse(result.stdout)
                else LOG.warn("Working directory state is unavailable: ${HgFailure.message(result)}")
            } finally {
                val state = probed
                onEdt { execute(fileChanges.repoStateProbed(probe, state, busy)) }
            }
        }
    }

    private fun askAboutUnlistedPaths(ask: FileChangeCommand.AskUnlisted) {
        val repoRoot = comparison.repoRoot ?: return
        val untracked = showUntracked
        ApplicationManager.getApplication().executeOnPooledThread {
            var answer: HgResult? = null
            try {
                answer = HgCommandRunner(repoRoot).runToText(UnlistedPathsProbe.arguments(ask.keys, untracked))
            } finally {
                val result = answer
                onEdt {
                    val unlisted = UnlistedPathsProbe.answer(result, untracked)
                    execute(fileChanges.unlistedAnswered(ask, unlisted, conditions()))
                }
            }
        }
    }

    private fun scanTodos(trace: LoadTrace? = null) {
        val repoRoot = comparison.repoRoot ?: return
        val sources = ArrayList(sourceFiles)
        val requestId = ++todoScanId
        ApplicationManager.getApplication().executeOnPooledThread {
            val todoItems = ArrayList<HgTodoItem>()
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

    private fun openFile(row: HgChangeRow) {
        val item = row.file
        val repoRoot = comparison.repoRoot ?: return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(repoRoot, item.path)) ?: return
        val line = (row as? HgTodoItem)?.let { CaretTransfer.fileTarget(null, it.lineNumber) }
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
                requested.caret,
                requestFocus = true
            )
        }
    }

    fun reloadForSettings(changesetsOnly: Boolean) {
        if (changesetsOnly && !displayMode.isChangesets) return
        onEdt { refresh() }
    }

    fun reloadForBranchLimit() = onEdt { if (displayMode.usesRevisions) refresh() }

    fun loadForRequestedDiff() = onEdt { if (!busy && !isLoaded()) refresh() }

    fun isLoaded(): Boolean = comparison.repoRoot != null

    fun diffTabKeyOf(file: VirtualFile): String? = itemFor(file)?.let { HgPaths.key(it.path) }

    private fun itemFor(file: VirtualFile): HgFileItem? {
        if (file.isDirectory || !file.isInLocalFileSystem) return null
        return ChangesDiffSource.changedItem(comparison, file.path)
    }

    private fun diffFile(item: HgFileItem) {
        val repoRoot = comparison.repoRoot ?: return
        val localFile = File(repoRoot, item.path)
        NavigationHistory.record(project) {
            project.service<HgChangesService>()
                .openDiffTab(localFile.path, HgPaths.key(item.path), tree)
        }
    }

    private fun comparisonSignature(): String = ComparisonSignature.of(
        sourceFiles, comparison.mode, comparison.baseRev, ChangesDiffSource.changesetsStamp()
    )

    private fun prefetchAround(activated: HgFileItem) {
        val state = comparison
        if (state.repoRoot == null) return
        val items = PrefetchPlan.around(visibleFiles(), FileKeys.of(activated), FileKeys::of)
            .filterIsInstance<HgFileItem>()
        diffSource.prefetch(state, items)
    }

    private fun revertSelected() {
        val state = comparison
        val repoRoot = state.repoRoot ?: return
        val selected = selectedFiles().distinctBy { it.path }
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

    private fun onEdt(task: () -> Unit) = ApplicationManager.getApplication().invokeLater(task, expired)

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
        if (busy) return
        flushPendingStatsKeys()
        if (fileChanges.workPending) scheduleFileChangeReaction()
    }

    private companion object {

        val LOG = Logger.getInstance(HgChangesPanel::class.java)

        val jobs = ParallelJobs(PooledTaskLauncher)

        const val REVISIONS_PROPORTION_KEY = "mercurial.revisionsProportion"
        const val DEFAULT_TREE_PROPORTION = 0.75f

        const val FILTER_DEBOUNCE_MS = 500

        const val BRANCH_RELOAD_DEBOUNCE_MS = 700

        const val BRANCH_FIELD_WIDTH = 160

        const val SELECTION_DEBOUNCE_MS = 250

        const val DOUBLE_CLICK_GUARD_MS = 300

        const val REVISIONS_DEBOUNCE_MS = 300

        const val FILE_CHANGE_DELAY_MS = 400

        const val BUSY_RETRY_DELAY_MS = 2000

        const val COL_TREE = 0
        const val COL_STATS = 1
        const val COL_EYE = 2

        const val STATS_WIDTH = 78
        const val EYE_WIDTH = 26
    }
}

private fun <T> inReadAction(compute: () -> T): T =
    ApplicationManager.getApplication().runReadAction(Computable(compute))
