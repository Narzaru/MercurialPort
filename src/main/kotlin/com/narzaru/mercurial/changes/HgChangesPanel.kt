package com.narzaru.mercurial.changes

import com.narzaru.mercurial.diff.HgDiffTabManager
import com.narzaru.mercurial.hg.HgCommandRunner
import com.narzaru.mercurial.hg.HgContentCache
import com.narzaru.mercurial.hg.HgDiffStatParser
import com.narzaru.mercurial.hg.HgOutputDecoder
import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.hg.HgSettingsConfigurable
import com.narzaru.mercurial.hg.HgStatusParser
import com.narzaru.mercurial.history.HgFileHistoryService
import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgListMode
import com.narzaru.mercurial.status.HgFileStatusService
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowEx
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * Главное окно плагина: дерево изменённых файлов Mercurial в стиле Upsource —
 * с отметками «просмотрено», статистикой +/- по файлам, режимами сравнения,
 * фильтрами, открытием/диффом/откатом и режимом TODO.
 *
 * Разбор вывода `hg` живёт в пакете `hg`, отметки — в [ReviewState], отрисовка строк —
 * в [ChangesTreeRenderers]; здесь остаётся сборка UI и оркестровка.
 */
class HgChangesPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val settings = ChangesSettings(project)
    private val review = ReviewState(settings)

    // Состояние
    private var displayMode = HgDisplayMode.UNCOMMITTED
    private var listMode = HgListMode.FILES
    private var showUntracked = false
    private var showUnchanged = false
    private var filtersVisible = false
    private var statsVisible = true
    private var currentRepoRoot: File? = null
    private var currentBaseRev: String = "."
    private var busy = false
    private val sourceFiles = ArrayList<HgFileItem>()
    private var currentTodoItems: List<HgFileItem> = emptyList()

    private val debounceAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
    private val todoAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)

    /** Откладывает дифф и отметку при переходе стрелками, пока список листают насквозь. */
    private val selectionAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)

    /** Счётчик запросов диффа: результат устаревшего клика игнорируется. */
    private var diffRequestId = 0

    /** То же для упреждающего чтения соседей: пачка от прошлой строки бросается на полпути. */
    private var prefetchId = 0

    /**
     * Содержимое файлов на базовой ревизии. Оно неизменно, а каждый `hg cat` стоит
     * запуска Mercurial целиком — четверть секунды до начала полезной работы.
     */
    private val baseContentCache = HgContentCache()

    /** То же для фонового подсчёта +/-, плюс флаг «считается прямо сейчас». */
    private var statsRequestId = 0
    private var statsPending = false

    // UI
    private val filterField = JBTextField()
    private val excludeField = JBTextField()
    private val branchField = JBTextField(10)
    private val modeCombo = JComboBox(HgDisplayMode.entries.toTypedArray())
    private val branchLabel = JBLabel(" ")
    private val summaryLabel = JBLabel(" ")
    private val filtersRow = JPanel(BorderLayout(4, 0))
    private val modeRow = JPanel(BorderLayout(4, 0))

    /**
     * Holder of [branchField]. Hidden as a whole outside `VS branch`: hiding just the field leaves
     * the holder's own padding on the right, and the mode combo ends short of the fields below it.
     */
    private val branchHolder = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))

    /**
     * Узлы, текст которых рендерер обрезал многоточием, — только для них показываем тултип.
     * Ключи слабые: дерево пересобирается целиком на каждый renderFiltered().
     */
    private val truncatedNodes: MutableSet<DefaultMutableTreeNode> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    // Рендереры объявлены до дерева: свойство, объявленное ниже места использования,
    // к моменту настройки колонок ещё не проинициализировано (`by lazy` не спасает —
    // делегат тоже поле и инициализируется по порядку объявления).
    private val statsRenderer: TableCellRenderer = StatsCellRenderer { nodeAt(it) }
    private val eyeRenderer: TableCellRenderer = ReviewCellRenderer({ nodeAt(it) }, ::allReviewed)

    /**
     * Снимает блокировку активации строк. Объявлено до дерева: TreeTable трогает выделение
     * уже из своего конструктора, а свойство, объявленное ниже места использования, к тому
     * моменту ещё не проинициализировано.
     */
    private var uiReady = false

    private val treeRoot = DefaultMutableTreeNode(DirNode(""))
    private val treeModel = ListTreeTableModelOnColumns(treeRoot, buildColumns())

    // Подсказку считаем сами по строке под курсором: рендереры переиспользуют один
    // компонент, и выставленный в них toolTipText JTable отдаёт от «чужой» строки
    // (либо не отдаёт вовсе). Здесь, вне рендерера, звать API дерева уже безопасно.
    private val tree = object : TreeTable(treeModel) {
        override fun getToolTipText(event: MouseEvent): String? = tooltipAt(event)

        /** true, пока строку выбирает мышь: клики обрабатываются отдельно и без задержки. */
        private var mouseDriven = false

        override fun processMouseEvent(e: MouseEvent) {
            // UI меняет выделение внутри этого вызова, поэтому происхождение смены
            // видно только отсюда — в самом changeSelection мыши от клавиатуры не отличить.
            mouseDriven = true
            try {
                super.processMouseEvent(e)
            } finally {
                mouseDriven = false
            }
        }

        override fun changeSelection(row: Int, column: Int, toggle: Boolean, extend: Boolean) {
            super.changeSelection(row, column, toggle, extend)
            if (!mouseDriven) onKeyboardSelection(row)
        }
    }

    init {
        buildUi()
        loadSettings()
        uiReady = true
        followActiveEditor()
        refresh()
    }

    override fun dispose() = Unit

    // region Выбор строки -----------------------------------------------------

    /**
     * Выбор файла — и есть его просмотр: показываем дифф и ставим отметку. Единая точка
     * для клика мышью и для перехода стрелками.
     */
    private fun activateRow(row: Int) {
        val item = (nodeAt(row)?.userObject as? FileNode)?.item ?: return
        syncFileHistory(item)
        if (item.isTodoItem) return
        diffFile(item)
        if (settings.markReviewedOnOpen && review.set(listOf(item), true)) renderFiltered()
        prefetchAround(row)
    }

    /**
     * Стрелками список пролистывают насквозь, поэтому дифф и отметка ждут остановки:
     * иначе проход по ветке в полсотни файлов пометил бы их все и породил столько же
     * процессов `hg cat`, из которых пригодился бы последний.
     */
    private fun onKeyboardSelection(row: Int) {
        // TreeTable трогает выделение уже в своём конструкторе, когда поля панели
        // (и сама ссылка на дерево) ещё не проинициализированы.
        if (!uiReady) return
        selectionAlarm.cancelAllRequests()
        selectionAlarm.addRequest({ activateRow(row) }, SELECTION_DEBOUNCE_MS)
    }

    /**
     * Highlights the file opened in the editor, the way `Always Select Opened File` works for
     * the project view. Selection only — no diff and no review mark: during a review one jumps
     * to a neighbouring class just to read it, and that must not count as reviewing it.
     */
    private fun followActiveEditor() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (!settings.selectOpenedFile) return
                    val file = event.newFile ?: return
                    // A diff tab is not a file on disk: its path is not in the list anyway.
                    if (file.isDirectory || !file.isInLocalFileSystem) return
                    selectOpenedFile(file)
                }
            }
        )
    }

    /**
     * Selects the row of the opened file, and clears the selection when the file is not in the
     * list: a stale highlight on another row would read as "this file is changed", which is
     * exactly the question being asked.
     */
    private fun selectOpenedFile(file: VirtualFile) {
        val repoRoot = currentRepoRoot ?: return
        val relative = HgPaths.relativize(
            HgPaths.normalize(file.path),
            // VFS reports paths with `/`, File.absolutePath on Windows with `\`.
            HgPaths.normalize(repoRoot.absolutePath)
        ) ?: return
        val key = HgPaths.key(relative)
        // A pending row activation from arrow-key scrolling is moot once we move elsewhere.
        selectionAlarm.cancelAllRequests()
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

    // endregion

    // region UI ---------------------------------------------------------------

    private fun buildUi() {
        val north = JPanel()
        north.layout = javax.swing.BoxLayout(north, javax.swing.BoxLayout.Y_AXIS)
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
        // Заголовки колонок здесь ничего не поясняют, а строку занимают. Снимаем их
        // со скроллпейна, а не через tree.tableHeader = null — иначе TreeTable падает
        // при перестройке колонок и дерево остаётся пустым.
        scroll.setColumnHeaderView(null)
        add(scroll, BorderLayout.CENTER)

        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                north.revalidate()
                north.repaint()
            }
        })
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup()
        group.add(action("Refresh", "Reload the changes", AllIcons.Actions.Refresh) { refresh() })
        group.add(action("Undo Changes", "Revert the selected files", AllIcons.Actions.Rollback) { revertSelected() })
        group.add(Separator.getInstance())
        group.add(toggle("Files", "List of files", AllIcons.Actions.ListFiles,
            { listMode == HgListMode.FILES }) { setListMode(HgListMode.FILES) })
        group.add(toggle("TODO", "TODO comments in the changed files", AllIcons.General.TodoDefault,
            { listMode == HgListMode.TODO }) { setListMode(HgListMode.TODO) })
        group.add(Separator.getInstance())
        group.add(toggle("Show Untracked", "Show untracked files (?)", HgIcons.EYE_CROSSED,
            { showUntracked }) { showUntracked = !showUntracked; refresh() })
        group.add(toggle("Show Unchanged", "Show files with no real changes (♦)", AllIcons.Vcs.Equal,
            { showUnchanged }) { setShowUnchanged(!showUnchanged) })
        group.add(toggle("Filter", "Show the Filter/Exclude fields", AllIcons.General.Filter,
            { filtersVisible }) { setFiltersVisible(!filtersVisible) })

        val toolbar = ActionManager.getInstance().createActionToolbar("HgChanges", group, true)
        toolbar.targetComponent = this
        toolbar.setLayoutPolicy(ActionToolbar.WRAP_LAYOUT_POLICY)
        return toolbar.component
    }

    private fun buildModeRow(): JPanel {
        modeRow.border = JBUI.Borders.empty(0, 4, 2, 4)
        // Пояснение к режиму — тултипом: и у пунктов списка, и у самого поля, иначе разницу
        // между двумя режимами ветки видно только по цифрам в готовом списке.
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
        val fields = JPanel(java.awt.GridLayout(1, 2, 4, 0))
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

    private fun action(text: String, description: String, icon: Icon?, perform: () -> Unit): AnAction =
        object : AnAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = perform()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !busy
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun toggle(
        text: String,
        description: String,
        icon: Icon?,
        selected: () -> Boolean,
        perform: () -> Unit
    ): ToggleAction = object : ToggleAction(text, description, icon) {
        override fun isSelected(e: AnActionEvent) = selected()
        override fun setSelected(e: AnActionEvent, state: Boolean) = perform()
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    // endregion

    // region Дерево -----------------------------------------------------------

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
        tree.selectionModel.selectionMode = javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        tree.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        applyColumnWidths()
        // Без явной регистрации ToolTipManager не опрашивает таблицу и getToolTipText не зовётся.
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree)
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = handleClick(e)
        })
        installReviewToggleShortcut()
    }

    private fun markTruncated(node: DefaultMutableTreeNode, truncated: Boolean) {
        if (truncated) truncatedNodes.add(node) else truncatedNodes.remove(node)
    }

    private fun applyColumnWidths() {
        column(COL_STATS)?.apply {
            minWidth = STATS_WIDTH; maxWidth = STATS_WIDTH; preferredWidth = STATS_WIDTH; resizable = false
            // TreeTable не спрашивает рендерер у ColumnInfo — задаём его на самой колонке.
            cellRenderer = statsRenderer
        }
        column(COL_EYE)?.apply {
            minWidth = EYE_WIDTH; maxWidth = EYE_WIDTH; preferredWidth = EYE_WIDTH; resizable = false
            cellRenderer = eyeRenderer
        }
        applyStatsColumnVisibility()
    }

    /** Колонка по индексу в модели: при скрытом `±` порядок в columnModel уже другой. */
    private fun column(modelIndex: Int): javax.swing.table.TableColumn? =
        (0 until tree.columnModel.columnCount)
            .map { tree.columnModel.getColumn(it) }
            .firstOrNull { it.modelIndex == modelIndex }

    private fun applyStatsColumnVisibility() {
        val existing = column(COL_STATS)
        when {
            statsVisible && existing == null -> {
                val restored = javax.swing.table.TableColumn(COL_STATS, STATS_WIDTH, statsRenderer, null).apply {
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

    /** Пункты в меню тул-окна (⋮) — редко используемые настройки панели. */
    fun installGearActions(toolWindow: ToolWindow) {
        if (toolWindow !is ToolWindowEx) return
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup(
                // Сброс отметок — команда, а не переключатель, и делает он много: держим его
                // отдельной группой сверху, чтобы не нажимался заодно с настройками панели.
                action("Clear Reviewed", "Drop every review mark", null) { clearReviewed() },
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
                    if (settings.selectOpenedFile) {
                        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                            ?.takeIf { !it.isDirectory && it.isInLocalFileSystem }
                            ?.let { selectOpenedFile(it) }
                    }
                },
                toggle(
                    "Status Letter in Editor Tabs",
                    "Append the file status to the editor tab title: Foo.cs [M]",
                    null,
                    { settings.statusInTabs }
                ) {
                    settings.statusInTabs = !settings.statusInTabs
                    project.service<HgFileStatusService>().refreshOpenTabs()
                },
                action("Encoding Settings…", "Encoding of commit messages and hg output", null) {
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

    /** Просмотрен ли узел целиком. У каталога берём готовый агрегат, а не обходим поддерево. */
    private fun allReviewed(node: DefaultMutableTreeNode): Boolean = when (val payload = node.userObject) {
        is FileNode -> review.isReviewed(payload.item)
        is DirNode -> payload.fileCount > 0 && payload.reviewedCount == payload.fileCount
        else -> false
    }

    /**
     * Полный текст строки под курсором — но только там, где рендерер обрезал его многоточием:
     * тултип-дубликат на каждой строке только мешает.
     */
    private fun tooltipAt(event: MouseEvent): String? {
        val viewColumn = tree.columnAtPoint(event.point)
        if (viewColumn < 0) return null
        val modelColumn = tree.convertColumnIndexToModel(viewColumn)
        if (modelColumn == COL_EYE) return ReviewCellRenderer.TOOLTIP
        if (modelColumn != COL_TREE) return null

        val node = nodeAt(tree.rowAtPoint(event.point)) ?: return null
        val payload = node.userObject
        // Откуда переименован файл, по строке видно только по имени — полный старый путь
        // показываем всегда, а не только когда текст не поместился.
        if (payload is FileNode && payload.item.copiedFrom.isNotEmpty()) {
            return "${payload.item.copiedFrom} → ${payload.item.path}"
        }
        if (node !in truncatedNodes) return null
        return when (payload) {
            is DirNode -> payload.name
            is FileNode -> payload.item.path
            else -> null
        }
    }

    private fun selectedNodes(): List<DefaultMutableTreeNode> =
        tree.selectedRows.toList().mapNotNull { nodeAt(it) }

    /**
     * Клик по «глазику» переключает отметку (у каталога — сразу для всех файлов внутри),
     * одинарный клик по файлу показывает дифф и отмечает просмотренным, двойной —
     * открывает файл.
     */
    private fun handleClick(e: MouseEvent) {
        if (e.button != MouseEvent.BUTTON1) return
        val viewRow = tree.rowAtPoint(e.point)
        val node = nodeAt(viewRow) ?: return

        // Сравниваем с индексом в модели: при скрытой колонке ± порядок на экране другой.
        val clickedColumn = tree.columnAtPoint(e.point)
        if (clickedColumn >= 0 && tree.convertColumnIndexToModel(clickedColumn) == COL_EYE) {
            // Отметку здесь ставят руками, поэтому обычную активацию строки не запускаем:
            // иначе клик по невыделенному глазику сначала отметил бы файл, а потом снял.
            if (e.clickCount == 1 && review.toggle(ChangesTreeBuilder.filesOf(node))) renderFiltered()
            return
        }

        val payload = node.userObject
        if (payload is DirNode) {
            // По колонке дерева двойной клик раскрывает узел сам: TreeTable отдаёт события
            // этой колонки самому дереву. Свой вызов сворачивал каталог и тут же разворачивал
            // обратно. В остальных колонках событие до дерева не доходит — там разворачиваем мы.
            val onTreeColumn = clickedColumn >= 0 && tree.convertColumnIndexToModel(clickedColumn) == COL_TREE
            if (e.clickCount == 2 && !onTreeColumn) toggleExpand(viewRow)
            return
        }
        val item = (payload as? FileNode)?.item ?: return
        // Клик мышью обрабатываем без задержки — в отличие от перехода стрелками,
        // где активацию строки приходится ждать (см. onKeyboardSelection).
        selectionAlarm.cancelAllRequests()
        when (e.clickCount) {
            1 -> activateRow(viewRow)
            2 -> openFile(item)
        }
    }

    /** Держит окно Hg File History на том же файле, что выбран здесь. */
    private fun syncFileHistory(item: HgFileItem) {
        val repoRoot = currentRepoRoot ?: return
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
        tree.actionMap.put(key, object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = toggleReviewedForSelection()
        })
    }

    // endregion

    // region Настройки --------------------------------------------------------

    private fun loadSettings() {
        filterField.text = settings.filter
        excludeField.text = settings.exclude
        branchField.text = settings.compareBranch
        // Только сохранённое значение: раньше непустой текст фильтра разворачивал поля
        // почти всегда, и «скрыть» переживало перезапуск лишь при пустых фильтрах.
        setFiltersVisible(settings.filtersVisible)
        showUnchanged = settings.showUnchanged
        statsVisible = settings.statsColumnVisible
        applyStatsColumnVisibility()
        review.reload()
        updateBranchFieldVisibility()
    }

    private fun saveSettings() {
        settings.filter = filterField.text ?: ""
        settings.exclude = excludeField.text ?: ""
        settings.compareBranch = branchField.text ?: ""
        settings.filtersVisible = filtersVisible
    }

    private fun setFiltersVisible(visible: Boolean) {
        filtersVisible = visible
        // Сохраняем сразу: debounce срабатывает только при правке текста фильтра,
        // так что иначе одно переключение тумблера до перезапуска не доживало.
        settings.filtersVisible = visible
        filtersRow.isVisible = visible
        revalidate()
        repaint()
    }

    private fun setShowUnchanged(visible: Boolean) {
        showUnchanged = visible
        settings.showUnchanged = visible
        // Флаг isUnchanged уже посчитан в loadDiffStats — перечитывать hg незачем.
        renderFiltered()
    }

    private fun updateBranchFieldVisibility() {
        val custom = displayMode == HgDisplayMode.CUSTOM_BRANCH
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

    // endregion

    // region Отметки «просмотрено» --------------------------------------------

    private fun toggleReviewedForSelection() {
        val items = selectedNodes()
            .flatMap { ChangesTreeBuilder.filesOf(it) }
            .distinctBy { review.key(it) }
        if (review.toggle(items)) renderFiltered()
    }

    private fun clearReviewed() {
        if (review.clear()) renderFiltered()
    }

    // endregion

    // region Режимы -----------------------------------------------------------

    private fun setDisplayMode(mode: HgDisplayMode) {
        displayMode = mode
        refresh()
    }

    private fun setListMode(mode: HgListMode) {
        if (listMode == mode) return
        listMode = mode
        if (mode == HgListMode.TODO) {
            scheduleTodoPolling()
            if (!busy) scanTodos()
        } else {
            todoAlarm.cancelAllRequests()
            renderFiltered()
        }
    }

    // endregion

    // region Логика hg --------------------------------------------------------

    private fun projectStartDir(): File? {
        val path = project.basePath ?: project.guessProjectDir()?.path ?: return null
        return File(path)
    }

    private fun refresh() {
        val start = projectStartDir()
        if (start == null) {
            showStatus("No project directory")
            return
        }
        val customBranch = branchField.text?.trim().orEmpty()
        if (displayMode == HgDisplayMode.CUSTOM_BRANCH && customBranch.isEmpty()) {
            sourceFiles.clear(); renderFiltered()
            showStatus("Error: Branch name cannot be empty.")
            return
        }

        setBusy(true)
        showStatus("Loading…")
        val mode = displayMode
        val untracked = showUntracked

        ApplicationManager.getApplication().executeOnPooledThread {
            val repoRoot = HgCommandRunner.findRepoRoot(start)
            if (repoRoot == null) {
                onEdt { setBusy(false); showStatus("No repo found") }
                return@executeOnPooledThread
            }
            val runner = HgCommandRunner(repoRoot)
            val result = computeChanges(runner, mode, customBranch, untracked)

            onEdt {
                setBusy(false)
                currentRepoRoot = repoRoot
                // Сбрасываем безусловно: базовая ревизия записывается символически (`.`),
                // и после коммита то же выражение указывает уже на другое содержимое —
                // по одному лишь ключу устаревшую запись не отличить.
                baseContentCache.clear()
                currentBaseRev = result.targetRev
                sourceFiles.clear()
                if (result.error != null) {
                    showStatus(result.error)
                } else {
                    sourceFiles.addAll(result.files)
                    showStatus(result.statusText)
                }
                publishStatuses()
                if (listMode == HgListMode.TODO) scanTodos() else renderFiltered()
                if (result.error == null && result.files.isNotEmpty()) {
                    loadDiffStats(repoRoot, result.targetRev)
                }
            }
        }
    }

    /**
     * Догружает `+N −M` отдельно от списка: дифф целой ветки считается секундами,
     * и ждать его, прежде чем показать файлы, незачем.
     */
    private fun loadDiffStats(repoRoot: File, targetRev: String) {
        val requestId = ++statsRequestId
        statsPending = true
        updateSummary(shownFiles())

        ApplicationManager.getApplication().executeOnPooledThread {
            val (exit, bytes) = HgCommandRunner(repoRoot).runToBytes(listOf("diff", "--git", "--rev", targetRev))
            val stats = if (exit == 0) HgDiffStatParser.parse(bytes) else emptyMap()
            onEdt {
                if (requestId != statsRequestId) return@onEdt // пришёл более свежий refresh
                statsPending = false
                for (i in sourceFiles.indices) {
                    val item = sourceFiles[i]
                    val stat = stats[HgPaths.normalize(item.path)]
                    val unchanged = item.status == "M" && stat == null
                    sourceFiles[i] = item.copy(
                        status = if (unchanged) UNCHANGED_STATUS else item.status,
                        isUnchanged = unchanged,
                        added = stat?.added ?: 0,
                        removed = stat?.removed ?: 0
                    )
                }
                publishStatuses()
                if (listMode == HgListMode.FILES) renderFiltered()
            }
        }
    }

    /**
     * Hands the current statuses to [HgFileStatusService], which puts them into editor tab
     * titles. Called after the stats pass as well: only there does an `M` with an empty diff
     * turn into [UNCHANGED_STATUS].
     */
    private fun publishStatuses() {
        val repoRoot = currentRepoRoot ?: return
        project.service<HgFileStatusService>().update(repoRoot, sourceFiles)
    }

    private class ChangesResult(
        val files: List<HgFileItem>,
        val targetRev: String,
        val statusText: String,
        val error: String?
    )

    private fun computeChanges(
        runner: HgCommandRunner,
        mode: HgDisplayMode,
        customBranch: String,
        untracked: Boolean
    ): ChangesResult {
        val targetRev = when (mode) {
            HgDisplayMode.CUSTOM_BRANCH -> customBranch
            HgDisplayMode.BRANCH -> BRANCH_START_REV
            HgDisplayMode.UNCOMMITTED -> "."
        }

        // The branch mode is limited to the files its own revisions touched: otherwise a parent
        // merged into the branch shows up as the branch's own work. `VS` compares everything.
        val scope = if (mode == HgDisplayMode.BRANCH) branchOwnFiles(runner) else null

        val template = StatusTextFormatter.REVISION_TEMPLATE
        val currentInfo = runner.run("log", "-r", ".", "--template", template)
            .stdout.ifBlank { StatusTextFormatter.UNKNOWN_REVISION }
        val baseInfo = runner.run("log", "-r", targetRev, "--template", template)
            .stdout.ifBlank { StatusTextFormatter.UNKNOWN_REVISION }

        val statusRes = runner.run(
            "status", "--rev", targetRev,
            HgStatusParser.statusFlags(untracked), HgStatusParser.COPIES_FLAG
        )
        if (!statusRes.success) {
            return ChangesResult(emptyList(), targetRev, "", statusError(mode, statusRes.stderr))
        }

        // Статусы показываем сразу, а +/- догружает loadDiffStats — дифф ветки слишком долгий.
        val all = HgStatusParser.foldRenames(HgStatusParser.parse(statusRes.stdout))
        val files = if (scope == null) all else all.filter { inScope(it, scope) }
        return ChangesResult(files, targetRev, StatusTextFormatter.branchInfo(mode, currentInfo, baseInfo), null)
    }

    private fun inScope(item: HgFileItem, scope: Set<String>): Boolean =
        HgPaths.key(item.path) in scope || (item.copiedFrom.isNotEmpty() && HgPaths.key(item.copiedFrom) in scope)

    /**
     * Файлы, которых касались собственные ревизии ветки, — то же множество, что показывает
     * Upsource для ревью «первая ревизия ветки … текущая». У ревизии слияния `{files}` содержит
     * только то, что правилось при слиянии, поэтому разрешение конфликтов в список попадает,
     * а просто влитые из родителя файлы — нет.
     *
     * `null` — множество получить не удалось (корневая ветка, ошибка hg): тогда список не
     * ограничиваем, это лучше пустого окна.
     */
    private fun branchOwnFiles(runner: HgCommandRunner): Set<String>? {
        val r = runner.run("log", "-r", BRANCH_REVS, "--template", "{join(files, '\\n')}\\n")
        if (!r.success) return null
        val files = r.stdout.split('\r', '\n')
            .mapNotNull { it.trim().ifEmpty { null } }
            .map { HgPaths.key(it) }
            .toSet()
        return files.ifEmpty { null }
    }

    /** A root branch has no parent and `hg status --rev` fails — point at a mode that works. */
    private fun statusError(mode: HgDisplayMode, stderr: String): String {
        val rootBranch = mode == HgDisplayMode.BRANCH &&
            // "empty revision range" is what the revset answers on a branch without a parent.
            (stderr.contains("revision 0") || stderr.contains("unknown revision") ||
                stderr.contains("empty revision"))
        return if (rootBranch) {
            "ROOT BRANCH DETECTED (No Parent). Use '${HgDisplayMode.UNCOMMITTED.title}'.\nDetails: " +
                stderr.trim()
        } else {
            "HG Error: " + stderr.trim()
        }
    }

    // endregion

    // region Отрисовка дерева -------------------------------------------------

    private fun shownFiles(): List<HgFileItem> {
        val filter = PathFilter(filterField.text.orEmpty(), excludeField.text.orEmpty())
        return (if (listMode == HgListMode.TODO) currentTodoItems else sourceFiles)
            .filter { (showUnchanged || !it.isUnchanged) && filter.accepts(it.path) }
    }

    private fun renderFiltered() {
        // Дерево пересобирается целиком, а отметка «просмотрено» перестраивает его на
        // каждый файл — без восстановления выделение слетало бы на каждом шаге ревью.
        val selected = selectedFileKeys()
        val items = shownFiles()
        val newRoot = ChangesTreeBuilder.build(items, review::isReviewed)
        treeModel.setRoot(newRoot)
        applyColumnWidths()
        TreeUtil.expandAll(tree.tree)
        restoreSelection(selected)
        updateSummary(items)
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

    /** Выделяет файл и подкручивает к нему список — панель показывает, где вы находитесь. */
    private fun selectFile(item: HgFileItem) {
        val key = HgPaths.key(item.path)
        for (row in 0 until tree.rowCount) {
            val payload = (nodeAt(row)?.userObject as? FileNode)?.item ?: continue
            if (HgPaths.key(payload.path) != key) continue
            tree.selectionModel.setSelectionInterval(row, row)
            tree.scrollRectToVisible(tree.getCellRect(row, 0, true))
            return
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

    // endregion

    // region TODO -------------------------------------------------------------

    private fun scheduleTodoPolling() {
        todoAlarm.cancelAllRequests()
        todoAlarm.addRequest({
            if (!busy && listMode == HgListMode.TODO) scanTodos()
            if (listMode == HgListMode.TODO) scheduleTodoPolling()
        }, TODO_POLL_MS)
    }

    private fun scanTodos() {
        val repoRoot = currentRepoRoot ?: return
        val sources = ArrayList(sourceFiles)
        ApplicationManager.getApplication().executeOnPooledThread {
            val todoItems = ArrayList<HgFileItem>()
            for (source in sources) {
                val text = readFileText(File(repoRoot, source.path))
                todoItems.addAll(TodoParser.parse(source, text))
            }
            onEdt {
                if (listMode != HgListMode.TODO) return@onEdt
                currentTodoItems = todoItems
                renderFiltered()
            }
        }
    }

    /** Текст берём из документа, если файл открыт: несохранённые TODO иначе не видны. */
    private fun readFileText(fullPath: File): String {
        val vf = LocalFileSystem.getInstance().findFileByIoFile(fullPath)
        if (vf != null) {
            val doc = ReadAction.compute<Document?, RuntimeException> {
                FileDocumentManager.getInstance().getDocument(vf)
            }
            if (doc != null) {
                return ReadAction.compute<String, RuntimeException> { doc.text }
            }
        }
        return try {
            if (fullPath.isFile) fullPath.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    // endregion

    // region Действия ---------------------------------------------------------

    private fun openFile(item: HgFileItem) {
        val repoRoot = currentRepoRoot ?: return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(repoRoot, item.path)) ?: return
        // Открываем сами — слежение за редактором иначе примет это за второе открытие
        // и повторит дифф с отметкой.
        // Первый щелчок двойного клика уже заказал дифф — снимаем его, иначе он
        // придёт из фонового потока следом и перебьёт только что открытый файл.
        diffRequestId++
        if (item.lineNumber > 0) {
            OpenFileDescriptor(project, vf, item.lineNumber - 1, 0).navigate(true)
        } else {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
        if (settings.markReviewedOnOpen && review.set(listOf(item), true)) renderFiltered()
    }

    /**
     * Показывает дифф файла в редакторе. Вкладка переиспользуется, фокус остаётся
     * в дереве. Новые (A/?) и удалённые (R/!) файлы сравниваются с пустой стороной.
     */
    private fun diffFile(item: HgFileItem) {
        val repoRoot = currentRepoRoot ?: return
        val localFile = File(repoRoot, item.path)
        // У переименования и копии базовая сторона есть — она лежит под старым именем.
        val isNew = (item.status == "A" || item.status == "?") && item.copiedFrom.isEmpty()
        val hasBaseRev = currentBaseRev.isNotEmpty() && currentBaseRev != "null"
        // Устаревший ответ не должен перекрыть свежий, даже если сами мы ничего не ждём.
        val requestId = ++diffRequestId

        // У новых и неотслеживаемых базовой стороны нет — спрашивать hg не о чем.
        if (isNew || !hasBaseRev) {
            showDiff(item, localFile, null)
            return
        }

        // Уже читали это содержимое — показываем сразу, без запуска hg и без мигания вкладки.
        val key = HgContentCache.key(currentBaseRev, item.basePath)
        baseContentCache.get(key)?.let {
            showDiff(item, localFile, it.text)
            return
        }

        val baseRev = currentBaseRev
        ApplicationManager.getApplication().executeOnPooledThread {
            val base = readBase(repoRoot, baseRev, item.basePath)
            onEdt {
                if (requestId != diffRequestId) return@onEdt // пришёл более свежий клик
                showDiff(item, localFile, base)
            }
        }
    }

    /** Содержимое файла на базовой ревизии, с укладкой в кэш. Зовётся из фонового потока. */
    private fun readBase(repoRoot: File, baseRev: String, path: String): String? {
        val key = HgContentCache.key(baseRev, path)
        baseContentCache.get(key)?.let { return it.text }
        val (exit, bytes) = HgCommandRunner(repoRoot).runToBytes(listOf("cat", "-r", baseRev, path))
        val text = if (exit == 0) HgOutputDecoder.decode(bytes) else null
        baseContentCache.put(key, text)
        return text
    }

    /**
     * Читает базовое содержимое соседних строк заранее. При ходьбе стрелками следующий
     * дифф к моменту нажатия уже готов — иначе каждый шаг упирается в старт hg.
     */
    private fun prefetchAround(row: Int) {
        val repoRoot = currentRepoRoot ?: return
        val baseRev = currentBaseRev
        if (baseRev.isEmpty() || baseRev == "null") return

        val paths = PREFETCH_OFFSETS
            .map { row + it }
            .filter { it >= 0 }
            .mapNotNull { (nodeAt(it)?.userObject as? FileNode)?.item }
            .filter { !it.isTodoItem && (it.copiedFrom.isNotEmpty() || (it.status != "A" && it.status != "?")) }
            .map { it.basePath }
        if (paths.isEmpty()) return

        val requestId = ++prefetchId
        ApplicationManager.getApplication().executeOnPooledThread {
            for (path in paths) {
                // Пользователь уже ушёл в другое место списка — дочитывать незачем.
                if (requestId != prefetchId) return@executeOnPooledThread
                readBase(repoRoot, baseRev, path)
            }
        }
    }

    private fun showDiff(item: HgFileItem, localFile: File, base: String?) {
        val factory = DiffContentFactory.getInstance()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(localFile.name)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(localFile)

        if (base == null && vf == null) {
            showStatus("Nothing to diff: ${item.path}")
            return
        }

        val baseContent = if (base != null) factory.create(project, base, fileType) else factory.createEmpty()
        val localContent = if (vf != null) factory.create(project, vf) else factory.createEmpty()
        // У переименования в заголовке базовой стороны — старый путь: иначе по вкладке
        // не понять, с чем именно сравнивают.
        val baseName = if (item.copiedFrom.isNotEmpty()) " ${item.copiedFrom}" else ""
        val baseTitle = if (base != null) "Hg Base ($currentBaseRev)$baseName" else "Not in base"
        val localTitle = if (vf != null) "Local Version" else "Deleted locally"

        val name = localFile.name
        val key = "$currentBaseRev|${item.path}"

        // Вкладками владеет общий менеджер: иначе это окно и Hg File History держат каждое
        // свою вкладку, и на экране оказывается два диффа сразу.
        val request = SimpleDiffRequest(name, baseContent, localContent, baseTitle, localTitle)
        project.service<HgDiffTabManager>().show(HgDiffTabManager.OWNER_CHANGES, key, name, request)
    }

    private fun revertSelected() {
        val repoRoot = currentRepoRoot ?: return
        val selected = selectedNodes()
            .flatMap { ChangesTreeBuilder.filesOf(it) }
            .distinctBy { it.path }
        if (selected.isEmpty()) return
        if (!confirmRevert(selected)) return

        setBusy(true)
        val mode = displayMode
        val baseRev = currentBaseRev
        ApplicationManager.getApplication().executeOnPooledThread {
            val runner = HgCommandRunner(repoRoot)
            val args = ArrayList<String>()
            args.add("revert"); args.add("--no-backup")
            if (mode != HgDisplayMode.UNCOMMITTED) {
                args.add("-r"); args.add(baseRev)
            }
            // Переименование откатываем вместе с источником: иначе старый путь останется
            // удалённым, а восстановится только новое имя.
            selected.forEach { args.add(it.path); if (it.copiedFrom.isNotEmpty()) args.add(it.copiedFrom) }
            val result = runner.run(args)

            val ioFiles = selected.flatMap { item ->
                listOfNotNull(File(repoRoot, item.path), item.copiedFrom.takeIf { it.isNotEmpty() }?.let { File(repoRoot, it) })
            }
            onEdt {
                LocalFileSystem.getInstance().refreshIoFiles(ioFiles)
                setBusy(false)
                if (!result.success && result.stderr.isNotBlank()) {
                    Messages.showErrorDialog(project, "Revert failed: ${result.stderr}", "Error")
                } else {
                    refresh()
                }
            }
        }
    }

    private fun confirmRevert(selected: List<HgFileItem>): Boolean {
        val message = buildString {
            if (displayMode == HgDisplayMode.UNCOMMITTED) {
                appendLine("Revert uncommitted changes in ${selected.size} file(s)?")
            } else {
                appendLine("Restore ${selected.size} file(s) to the base revision ($currentBaseRev)?")
                appendLine("Every change made since then, committed or not, will be lost.")
            }
            appendLine()
            selected.take(REVERT_PREVIEW_LIMIT).forEach { appendLine(it.path) }
            if (selected.size > REVERT_PREVIEW_LIMIT) {
                appendLine("... and ${selected.size - REVERT_PREVIEW_LIMIT} more")
            }
        }
        return Messages.showYesNoDialog(
            project, message, "Confirm revert", Messages.getWarningIcon()
        ) == Messages.YES
    }

    // endregion

    private fun onEdt(task: () -> Unit) = ApplicationManager.getApplication().invokeLater(task)

    private fun setBusy(value: Boolean) {
        busy = value
    }

    private companion object {
        /**
         * Точка ответвления: база режима «вся ветка» и ревизия, по которой определяется имя
         * родительской ветки. Ровно эту базу берёт Upsource для ревью «первая ревизия ветки …
         * текущая»: у первой ревизии ветки это и есть первый родитель.
         */
        const val BRANCH_START_REV = "p1(first(branch(.)))"

        /**
         * Последний предок текущей ревизии, лежащий на родительской ветке. Сам по себе базой
         * не служит, но отделяет собственные ревизии ветки от влитых: после слияния родителя
         * в ветку его ревизии тоже становятся её предками.
         */
        const val MERGE_BASE_REV = "max(ancestors(.) and branch($BRANCH_START_REV))"

        /**
         * Собственные ревизии ветки — предки текущей, не являющиеся предками точки слияния.
         * Их файлами ограничивается список: сравнение с точкой ответвления иначе тащит в него
         * всё, что родительская ветка успела наменять до слияния (889 файлов вместо 89).
         */
        const val BRANCH_REVS = "only(., $MERGE_BASE_REV)"

        /** Псевдостатус файла, который числится изменённым, но по диффу отличий не имеет. */
        const val UNCHANGED_STATUS = "♦"

        const val FILTER_DEBOUNCE_MS = 500

        /** Пауза, после которой остановка на строке считается выбором файла. */
        const val SELECTION_DEBOUNCE_MS = 250

        /**
         * Соседние строки, которые дочитываются заранее. Вниз заглядываем дальше, чем
         * вверх: список просматривают сверху вниз.
         */
        val PREFETCH_OFFSETS = listOf(1, 2, -1)
        const val TODO_POLL_MS = 1500
        const val REVERT_PREVIEW_LIMIT = 15

        const val COL_TREE = 0
        const val COL_STATS = 1
        const val COL_EYE = 2

        const val STATS_WIDTH = 78
        const val EYE_WIDTH = 26
    }
}
