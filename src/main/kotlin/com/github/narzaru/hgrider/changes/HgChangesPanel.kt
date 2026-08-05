package com.github.narzaru.hgrider.changes

import com.github.narzaru.hgrider.diff.HgDiffTabManager
import com.github.narzaru.hgrider.hg.HgCommandRunner
import com.github.narzaru.hgrider.hg.HgOutputDecoder
import com.github.narzaru.hgrider.hg.HgSettingsConfigurable
import com.github.narzaru.hgrider.history.HgFileHistoryService
import com.github.narzaru.hgrider.model.HgDiffStat
import com.github.narzaru.hgrider.model.HgDisplayMode
import com.github.narzaru.hgrider.model.HgFileItem
import com.github.narzaru.hgrider.model.HgListMode
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
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
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
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
import java.awt.Color
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTree
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
 */
class HgChangesPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val propertiesKeyPrefix = "hgrider."

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

    /** Пути файлов, отмеченных как «просмотрено» (нормализованные, в нижнем регистре). */
    private val reviewedPaths = HashSet<String>()

    private val debounceAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
    private val todoAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)

    /** Счётчик запросов диффа: результат устаревшего клика игнорируется. */
    private var diffRequestId = 0

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
     * Узлы, текст которых рендерер обрезал многоточием, — только для них показываем тултип.
     * Ключи слабые: дерево пересобирается целиком на каждый renderFiltered().
     */
    private val truncatedNodes: MutableSet<DefaultMutableTreeNode> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    private val treeRoot = DefaultMutableTreeNode(DirNode(""))
    private val treeModel = ListTreeTableModelOnColumns(treeRoot, buildColumns())

    // Подсказку считаем сами по строке под курсором: рендереры переиспользуют один
    // компонент, и выставленный в них toolTipText JTable отдаёт от «чужой» строки
    // (либо не отдаёт вовсе). Здесь, вне рендерера, звать API дерева уже безопасно.
    private val tree = object : TreeTable(treeModel) {
        override fun getToolTipText(event: MouseEvent): String? = tooltipAt(event)
    }

    // Рендереры колонок объявлены до init: колонки настраиваются уже из конструктора,
    // и свойство, объявленное ниже, к этому моменту ещё не проинициализировано.

    // Рендереры переиспользуют один компонент: на дереве в тысячи строк создание
    // нового Swing-компонента на каждую ячейку заметно тормозит отрисовку.

    private val statsComponent = SimpleColoredComponent().apply { isOpaque = true }

    /** Прижимает счётчик к правому краю колонки. */
    private val statsHolder = JPanel(BorderLayout()).apply {
        isOpaque = true
        add(statsComponent, BorderLayout.EAST)
    }

    /** Колонка `+N −M`, выровненная по правому краю. */
    private val statsRenderer = TableCellRenderer { table, _, isSelected, _, row, _ ->
        statsComponent.clear()
        val background = if (isSelected) table.selectionBackground else table.background
        statsComponent.background = background
        statsHolder.background = background
        val (added, removed) = when (val payload = nodeAt(row)?.userObject) {
            is FileNode -> payload.item.added to payload.item.removed
            is DirNode -> payload.added to payload.removed
            else -> 0 to 0
        }
        if (added > 0) {
            statsComponent.append("+$added ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ADDED_COLOR))
        }
        if (removed > 0) {
            statsComponent.append("−$removed", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, REMOVED_COLOR))
        }
        statsHolder
    }

    private val eyeComponent = JBLabel().apply {
        isOpaque = true
        horizontalAlignment = SwingConstants.CENTER
        border = BorderFactory.createEmptyBorder()
        toolTipText = EYE_TOOLTIP
    }

    /** Колонка отметки просмотра. */
    private val eyeRenderer = TableCellRenderer { table, _, isSelected, _, row, _ ->
        eyeComponent.background = if (isSelected) table.selectionBackground else table.background
        val node = nodeAt(row)
        eyeComponent.icon = when {
            node == null -> null
            allReviewed(node) -> EYE_ICON
            else -> EYE_ICON_FADED
        }
        eyeComponent
    }

    init {
        buildUi()
        loadSettings()
        refresh()
    }

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
        group.add(action("Refresh", "Перечитать изменения", AllIcons.Actions.Refresh) { refresh() })
        group.add(action("Undo Changes", "Откатить выбранные файлы", AllIcons.Actions.Rollback) { revertSelected() })
        group.add(action("Clear Reviewed", "Снять все отметки просмотра", AllIcons.General.Reset) { clearReviewed() })
        group.add(Separator.getInstance())
        group.add(toggle("Files", "Список файлов", AllIcons.Actions.ListFiles,
            { listMode == HgListMode.FILES }) { setListMode(HgListMode.FILES) })
        group.add(toggle("TODO", "TODO-комментарии в изменённых файлах", AllIcons.General.TodoDefault,
            { listMode == HgListMode.TODO }) { setListMode(HgListMode.TODO) })
        group.add(Separator.getInstance())
        group.add(toggle("Show Untracked", "Показывать неотслеживаемые файлы (?)", AllIcons.Vcs.Ignore_file,
            { showUntracked }) { showUntracked = !showUntracked; refresh() })
        group.add(toggle("Show Unchanged", "Показывать файлы без реальных изменений (♦)", AllIcons.Vcs.Equal,
            { showUnchanged }) { setShowUnchanged(!showUnchanged) })
        group.add(toggle("Filter", "Показать поля Filter/Exclude", AllIcons.General.Filter,
            { filtersVisible }) { setFiltersVisible(!filtersVisible) })

        val toolbar = ActionManager.getInstance().createActionToolbar("HgChanges", group, true)
        toolbar.targetComponent = this
        toolbar.setLayoutPolicy(ActionToolbar.WRAP_LAYOUT_POLICY)
        return toolbar.component
    }

    private fun buildModeRow(): JPanel {
        modeRow.border = JBUI.Borders.empty(0, 4, 2, 4)
        modeCombo.renderer = SimpleListCellRenderer.create("") { it?.title ?: "" }
        modeCombo.selectedItem = displayMode
        modeCombo.addActionListener {
            val selected = modeCombo.selectedItem as? HgDisplayMode ?: return@addActionListener
            if (selected != displayMode) setDisplayMode(selected)
            updateBranchFieldVisibility()
        }
        modeRow.add(modeCombo, BorderLayout.CENTER)

        val branchHolder = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
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
        tree.setTreeCellRenderer(NodeRenderer())
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

    private fun applyColumnWidths() {
        column(COL_STATS)?.apply {
            minWidth = 78; maxWidth = 78; preferredWidth = 78; resizable = false
            // TreeTable не спрашивает рендерер у ColumnInfo — задаём его на самой колонке.
            cellRenderer = statsRenderer
        }
        column(COL_EYE)?.apply {
            minWidth = 26; maxWidth = 26; preferredWidth = 26; resizable = false
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
                val restored = javax.swing.table.TableColumn(COL_STATS, 78, statsRenderer, null).apply {
                    minWidth = 78; maxWidth = 78; resizable = false
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
        props().setValue("${propertiesKeyPrefix}statsColumn", visible, true)
        applyStatsColumnVisibility()
        tree.repaint()
    }

    /** Пункты в меню тул-окна (⋮) — редко используемые настройки панели. */
    fun installGearActions(toolWindow: ToolWindow) {
        if (toolWindow !is ToolWindowEx) return
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup(
                toggle("Show ± Column", "Показывать колонку добавленных/удалённых строк", null,
                    { statsVisible }) { setStatsVisible(!statsVisible) },
                action("Encoding Settings…", "Кодировка сообщений коммитов и вывода hg", null) {
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

    /**
     * Полный текст строки под курсором — но только там, где рендерер обрезал его многоточием:
     * тултип-дубликат на каждой строке только мешает.
     */
    private fun tooltipAt(event: MouseEvent): String? {
        val viewColumn = tree.columnAtPoint(event.point)
        if (viewColumn < 0) return null
        val modelColumn = tree.convertColumnIndexToModel(viewColumn)
        if (modelColumn == COL_EYE) return EYE_TOOLTIP
        if (modelColumn != COL_TREE) return null

        val node = nodeAt(tree.rowAtPoint(event.point)) ?: return null
        if (node !in truncatedNodes) return null
        return when (val payload = node.userObject) {
            is DirNode -> payload.name
            is FileNode -> payload.item.path
            else -> null
        }
    }

    private fun selectedNodes(): List<DefaultMutableTreeNode> =
        tree.selectedRows.toList().mapNotNull { nodeAt(it) }

    /**
     * Клик по «глазику» переключает отметку (у каталога — сразу для всех файлов внутри),
     * одинарный клик по файлу сразу показывает дифф, двойной — открывает файл.
     */
    private fun handleClick(e: MouseEvent) {
        if (e.button != MouseEvent.BUTTON1) return
        val viewRow = tree.rowAtPoint(e.point)
        val node = nodeAt(viewRow) ?: return

        // Сравниваем с индексом в модели: при скрытой колонке ± порядок на экране другой.
        val clickedColumn = tree.columnAtPoint(e.point)
        if (clickedColumn >= 0 && tree.convertColumnIndexToModel(clickedColumn) == COL_EYE) {
            if (e.clickCount == 1) {
                val files = ChangesTreeBuilder.filesOf(node)
                setReviewed(files, files.any { !isReviewed(it) })
            }
            return
        }

        val payload = node.userObject
        if (payload is DirNode) {
            if (e.clickCount == 2) toggleExpand(viewRow)
            return
        }
        val item = (payload as? FileNode)?.item ?: return
        syncFileHistory(item)
        when (e.clickCount) {
            1 -> if (!item.isTodoItem) diffFile(item)
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
        val key = "hgrider.toggleReviewed"
        tree.inputMap.put(KeyStroke.getKeyStroke("SPACE"), key)
        tree.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke("SPACE"), key)
        tree.actionMap.put(key, object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = toggleReviewedForSelection()
        })
    }

    // endregion

    // region Настройки --------------------------------------------------------

    private fun props() = com.intellij.ide.util.PropertiesComponent.getInstance(project)

    private fun loadSettings() {
        val p = props()
        filterField.text = p.getValue("${propertiesKeyPrefix}filter", "")
        excludeField.text = p.getValue("${propertiesKeyPrefix}exclude", "")
        branchField.text = p.getValue("${propertiesKeyPrefix}compareBranch", "default")
        // Только сохранённое значение: раньше непустой текст фильтра разворачивал поля
        // почти всегда, и «скрыть» переживало перезапуск лишь при пустых фильтрах.
        setFiltersVisible(p.getBoolean("${propertiesKeyPrefix}filtersVisible", false))
        showUnchanged = p.getBoolean("${propertiesKeyPrefix}showUnchanged", false)
        statsVisible = p.getBoolean("${propertiesKeyPrefix}statsColumn", true)
        applyStatsColumnVisibility()
        reviewedPaths.clear()
        p.getList("${propertiesKeyPrefix}reviewed")?.let { reviewedPaths.addAll(it) }
        updateBranchFieldVisibility()
    }

    private fun saveSettings() {
        val p = props()
        p.setValue("${propertiesKeyPrefix}filter", filterField.text ?: "")
        p.setValue("${propertiesKeyPrefix}exclude", excludeField.text ?: "")
        p.setValue("${propertiesKeyPrefix}compareBranch", branchField.text ?: "")
        p.setValue("${propertiesKeyPrefix}filtersVisible", filtersVisible)
    }

    private fun setFiltersVisible(visible: Boolean) {
        filtersVisible = visible
        // Сохраняем сразу: debounce срабатывает только при правке текста фильтра,
        // так что иначе одно переключение тумблера до перезапуска не доживало.
        props().setValue("${propertiesKeyPrefix}filtersVisible", visible, false)
        filtersRow.isVisible = visible
        revalidate()
        repaint()
    }

    private fun setShowUnchanged(visible: Boolean) {
        showUnchanged = visible
        props().setValue("${propertiesKeyPrefix}showUnchanged", visible, false)
        // Флаг isUnchanged уже посчитан в loadDiffStats — перечитывать hg незачем.
        renderFiltered()
    }

    private fun updateBranchFieldVisibility() {
        branchField.isVisible = displayMode == HgDisplayMode.CUSTOM_BRANCH
        modeRow.revalidate()
        modeRow.repaint()
    }

    private fun triggerDebounce() {
        debounceAlarm.cancelAllRequests()
        debounceAlarm.addRequest({
            saveSettings()
            renderFiltered()
        }, 500)
    }

    // endregion

    // region Отметки «просмотрено» --------------------------------------------

    private fun reviewKey(item: HgFileItem) = item.path.replace('\\', '/').lowercase()

    private fun isReviewed(item: HgFileItem) = reviewedPaths.contains(reviewKey(item))

    /** Просмотрен ли узел целиком. У каталога берём готовый агрегат, а не обходим поддерево. */
    private fun allReviewed(node: DefaultMutableTreeNode): Boolean = when (val payload = node.userObject) {
        is FileNode -> isReviewed(payload.item)
        is DirNode -> payload.fileCount > 0 && payload.reviewedCount == payload.fileCount
        else -> false
    }

    private fun setReviewed(items: List<HgFileItem>, reviewed: Boolean) {
        var changed = false
        for (item in items) {
            val key = reviewKey(item)
            changed = (if (reviewed) reviewedPaths.add(key) else reviewedPaths.remove(key)) || changed
        }
        if (!changed) return
        saveReviewed()
        refreshTreeAggregates()
    }

    private fun toggleReviewedForSelection() {
        val items = selectedNodes().flatMap { ChangesTreeBuilder.filesOf(it) }.distinctBy { reviewKey(it) }
        if (items.isEmpty()) return
        setReviewed(items, items.any { !isReviewed(it) })
    }

    private fun clearReviewed() {
        if (reviewedPaths.isEmpty()) return
        reviewedPaths.clear()
        saveReviewed()
        refreshTreeAggregates()
    }

    private fun saveReviewed() {
        props().setList("${propertiesKeyPrefix}reviewed", reviewedPaths.toList())
    }

    /** Пересчитывает счётчики просмотра в каталогах и перерисовывает дерево. */
    private fun refreshTreeAggregates() {
        renderFiltered()
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
            setBranchInfo("No project directory", "")
            return
        }
        val customBranch = branchField.text?.trim().orEmpty()
        if (displayMode == HgDisplayMode.CUSTOM_BRANCH && customBranch.isEmpty()) {
            sourceFiles.clear(); renderFiltered()
            setBranchInfo("Error: Branch name cannot be empty.", "")
            return
        }

        setBusy(true)
        setBranchInfo("Loading…", "")
        val mode = displayMode
        val untracked = showUntracked

        ApplicationManager.getApplication().executeOnPooledThread {
            val repoRoot = HgCommandRunner.findRepoRoot(start)
            if (repoRoot == null) {
                onEdt { setBusy(false); setBranchInfo("No repo found", "") }
                return@executeOnPooledThread
            }
            val runner = HgCommandRunner(repoRoot)
            val result = computeChanges(runner, mode, customBranch, untracked)

            onEdt {
                setBusy(false)
                currentRepoRoot = repoRoot
                currentBaseRev = result.targetRev
                sourceFiles.clear()
                if (result.error != null) {
                    setBranchInfo(result.error, "")
                } else {
                    sourceFiles.addAll(result.files)
                    setBranchInfo(result.statusText, "")
                }
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
            val stats = collectDiffStats(HgCommandRunner(repoRoot), targetRev)
            onEdt {
                if (requestId != statsRequestId) return@onEdt // пришёл более свежий refresh
                statsPending = false
                for (i in sourceFiles.indices) {
                    val item = sourceFiles[i]
                    val stat = stats[item.path.replace('\\', '/')]
                    val unchanged = item.status == "M" && stat == null
                    sourceFiles[i] = item.copy(
                        status = if (unchanged) "♦" else item.status,
                        isUnchanged = unchanged,
                        added = stat?.added ?: 0,
                        removed = stat?.removed ?: 0
                    )
                }
                if (listMode == HgListMode.FILES) renderFiltered()
            }
        }
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
        var targetRev = "."
        when (mode) {
            HgDisplayMode.BASE_BRANCH_HEAD -> {
                val r = runner.run("log", "-r", "p1(first(branch(.)))", "--template", "{branch}")
                if (r.success) {
                    targetRev = r.stdout.trim()
                    if (targetRev.isEmpty()) {
                        return ChangesResult(emptyList(), ".", "", "Could not determine base branch name.")
                    }
                } else {
                    return ChangesResult(emptyList(), ".", "", "Root branch or error: " + r.stderr.trim())
                }
            }
            HgDisplayMode.CUSTOM_BRANCH -> targetRev = customBranch
            HgDisplayMode.BRANCH -> targetRev = "p1(first(branch(.)))"
            HgDisplayMode.UNCOMMITTED -> targetRev = "."
        }

        val currentInfo = runner.run("log", "-r", ".", "--template", template)
            .stdout.ifBlank { "Unknown|?|?" }
        val baseInfo = runner.run("log", "-r", targetRev, "--template", template)
            .stdout.ifBlank { "Unknown|?|?" }

        val flags = buildString {
            append("-ma")
            if (untracked) append("u")
        }

        val statusRes = runner.run("status", "--rev", targetRev, flags)
        if (!statusRes.success) {
            val err = statusRes.stderr
            val msg = if ((mode == HgDisplayMode.BRANCH || mode == HgDisplayMode.BASE_BRANCH_HEAD) &&
                (err.contains("revision 0") || err.contains("unknown revision"))
            ) {
                "ROOT BRANCH DETECTED (No Parent). Use 'Uncommitted Only'.\nDetails: " + err.trim()
            } else {
                "HG Error: " + err.trim()
            }
            return ChangesResult(emptyList(), targetRev, "", msg)
        }

        // Статусы показываем сразу, а +/- догружает loadDiffStats — дифф ветки слишком долгий.
        val files = statusRes.stdout.split('\r', '\n')
            .filter { it.length > 2 }
            .map { HgFileItem(status = it.substring(0, 1), path = it.substring(2).trim()) }

        return ChangesResult(files, targetRev, buildStatusText(mode, currentInfo, baseInfo), null)
    }

    /**
     * Считает добавленные/удалённые строки по файлам из `hg diff --git`. Даёт точные числа
     * (в отличие от `--stat`, где гистограмма масштабируется) и заодно показывает, какие
     * файлы изменились на самом деле.
     *
     * Разбор идёт прямо по байтам: дифф целой ветки — это мегабайты, и декодирование его
     * в строку со `split` подвешивало IDE.
     */
    private fun collectDiffStats(runner: HgCommandRunner, targetRev: String): Map<String, HgDiffStat> {
        val (exit, bytes) = runner.runToBytes(listOf("diff", "--git", "--rev", targetRev))
        if (exit != 0) return emptyMap()

        val result = HashMap<String, HgDiffStat>()
        var path: String? = null
        var added = 0
        var removed = 0

        fun flush() {
            path?.let { result[it] = HgDiffStat(added, removed) }
            added = 0
            removed = 0
        }

        var lineStart = 0
        while (lineStart < bytes.size) {
            var lineEnd = lineStart
            while (lineEnd < bytes.size && bytes[lineEnd] != NEW_LINE) lineEnd++
            var contentEnd = lineEnd
            if (contentEnd > lineStart && bytes[contentEnd - 1] == CARRIAGE_RETURN) contentEnd--

            if (contentEnd > lineStart) {
                when {
                    startsWith(bytes, lineStart, DIFF_GIT_PREFIX) -> {
                        flush()
                        path = extractGitPath(bytes, lineStart, contentEnd)
                    }
                    // Заголовки ---/+++ не считаем, они есть у каждого файла.
                    startsWith(bytes, lineStart, PLUS_HEADER) || startsWith(bytes, lineStart, MINUS_HEADER) -> Unit
                    bytes[lineStart] == PLUS -> added++
                    bytes[lineStart] == MINUS -> removed++
                }
            }
            lineStart = lineEnd + 1
        }
        flush()
        return result
    }

    private fun startsWith(bytes: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (offset + prefix.size > bytes.size) return false
        for (i in prefix.indices) {
            if (bytes[offset + i] != prefix[i]) return false
        }
        return true
    }

    /** Из строки `diff --git a/path b/path` берёт путь после ` b/`. */
    private fun extractGitPath(bytes: ByteArray, start: Int, end: Int): String? {
        var i = start
        while (i + B_SLASH.size <= end) {
            if (startsWith(bytes, i, B_SLASH)) {
                val from = i + B_SLASH.size
                if (from >= end) return null
                return HgOutputDecoder.decode(bytes.copyOfRange(from, end)).trim().ifEmpty { null }
            }
            i++
        }
        return null
    }

    private fun buildStatusText(mode: HgDisplayMode, currentInfo: String, baseInfo: String): String {
        fun fmt(raw: String): String {
            val parts = raw.split('|', limit = 3)
            return if (parts.size >= 3) "${parts[0]} (${parts[1]}) \"${parts[2]}\"" else raw
        }
        return if (mode == HgDisplayMode.UNCOMMITTED) {
            "Uncommitted in: ${fmt(currentInfo)}"
        } else {
            val relation = when (mode) {
                HgDisplayMode.BASE_BRANCH_HEAD -> " vs Head of: "
                HgDisplayMode.CUSTOM_BRANCH -> " vs Branch: "
                else -> " vs Parent: "
            }
            "Branch: ${fmt(currentInfo)}$relation${fmt(baseInfo)}"
        }
    }

    // endregion

    // region Отрисовка дерева -------------------------------------------------

    private fun shownFiles(): List<HgFileItem> =
        (if (listMode == HgListMode.TODO) currentTodoItems else sourceFiles)
            .filter { (showUnchanged || !it.isUnchanged) && passesFilter(it.path) }

    private fun renderFiltered() {
        val items = shownFiles()
        val newRoot = ChangesTreeBuilder.build(items) { isReviewed(it) }
        treeModel.setRoot(newRoot)
        applyColumnWidths()
        TreeUtil.expandAll(tree.tree)
        updateSummary(items)
    }

    private fun updateSummary(items: List<HgFileItem>) {
        val files = items.distinctBy { reviewKey(it) }
        val added = files.sumOf { it.added }
        val removed = files.sumOf { it.removed }
        val reviewed = files.count { isReviewed(it) }
        summaryLabel.text = when {
            files.isEmpty() -> " "
            statsPending -> "${files.size} files  ·  считаю ±…  ·  $reviewed/${files.size} reviewed"
            else -> "${files.size} files  +$added  −$removed  ·  $reviewed/${files.size} reviewed"
        }
    }

    private fun setBranchInfo(text: String, @Suppress("SameParameterValue") unused: String) {
        val singleLine = text.replace('\n', ' ')
        branchLabel.text = singleLine
        branchLabel.toolTipText = text
    }

    private fun passesFilter(path: String): Boolean {
        val exclude = excludeField.text?.trim().orEmpty()
        val filter = filterField.text?.trim().orEmpty()
        if (exclude.isNotEmpty() && matches(path, exclude)) return false
        if (filter.isEmpty()) return true
        return matches(path, filter)
    }

    private fun matches(path: String, patterns: String): Boolean {
        if (path.contains(patterns, ignoreCase = true)) return true
        return patterns.split(' ').filter { it.isNotBlank() }
            .any { path.contains(it, ignoreCase = true) }
    }

    // endregion

    // region TODO -------------------------------------------------------------

    private fun scheduleTodoPolling() {
        todoAlarm.cancelAllRequests()
        todoAlarm.addRequest({
            if (!busy && listMode == HgListMode.TODO) scanTodos()
            if (listMode == HgListMode.TODO) scheduleTodoPolling()
        }, 1500)
    }

    private fun scanTodos() {
        val repoRoot = currentRepoRoot ?: return
        val sources = ArrayList(sourceFiles)
        ApplicationManager.getApplication().executeOnPooledThread {
            val todoItems = ArrayList<HgFileItem>()
            for (source in sources) {
                val fullPath = File(repoRoot, source.path)
                val text = readFileText(fullPath)
                todoItems.addAll(TodoParser.parse(source, text))
            }
            onEdt {
                if (listMode != HgListMode.TODO) return@onEdt
                currentTodoItems = todoItems
                renderFiltered()
            }
        }
    }

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
        if (item.lineNumber > 0) {
            OpenFileDescriptor(project, vf, item.lineNumber - 1, 0).navigate(true)
        } else {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    /**
     * Показывает дифф файла в редакторе. Вкладка переиспользуется, фокус остаётся
     * в дереве. Новые (A/?) и удалённые (R/!) файлы сравниваются с пустой стороной.
     */
    private fun diffFile(item: HgFileItem) {
        val repoRoot = currentRepoRoot ?: return
        val localFile = File(repoRoot, item.path)
        val isNew = item.status == "A" || item.status == "?"
        val hasBaseRev = currentBaseRev.isNotEmpty() && currentBaseRev != "null"
        val requestId = ++diffRequestId

        ApplicationManager.getApplication().executeOnPooledThread {
            val base: String? = if (isNew || !hasBaseRev) {
                null
            } else {
                val runner = HgCommandRunner(repoRoot)
                val (exit, bytes) = runner.runToBytes(listOf("cat", "-r", currentBaseRev, item.path))
                if (exit == 0) HgOutputDecoder.decode(bytes) else null
            }
            onEdt {
                if (requestId != diffRequestId) return@onEdt // пришёл более свежий клик
                showDiff(item, localFile, base)
            }
        }
    }

    private fun showDiff(item: HgFileItem, localFile: File, base: String?) {
        val factory = DiffContentFactory.getInstance()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(localFile.name)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(localFile)

        if (base == null && vf == null) {
            setBranchInfo("Nothing to diff: ${item.path}", "")
            return
        }

        val baseContent = if (base != null) factory.create(project, base, fileType) else factory.createEmpty()
        val localContent = if (vf != null) factory.create(project, vf) else factory.createEmpty()
        val baseTitle = if (base != null) "Hg Base ($currentBaseRev)" else "Not in base"
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

        val message = buildString {
            if (displayMode == HgDisplayMode.UNCOMMITTED) {
                appendLine("Откатить незакоммиченные изменения (revert) для ${selected.size} файлов?")
            } else {
                appendLine("Вернуть ${selected.size} файлов к базовой ревизии ($currentBaseRev)?")
                appendLine("Все изменения с этого момента (включая незакоммиченные) будут потеряны.")
            }
            appendLine()
            selected.take(15).forEach { appendLine(it.path) }
            if (selected.size > 15) appendLine("... и ещё ${selected.size - 15} файлов")
        }

        val confirm = Messages.showYesNoDialog(
            project, message, "Подтверждение отката", Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

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
            selected.forEach { args.add(it.path) }
            val result = runner.run(args)

            val ioFiles = selected.map { File(repoRoot, it.path) }
            onEdt {
                LocalFileSystem.getInstance().refreshIoFiles(ioFiles)
                setBusy(false)
                if (!result.success && result.stderr.isNotBlank()) {
                    Messages.showErrorDialog(project, "Ошибка при откате: ${result.stderr}", "Ошибка")
                } else {
                    refresh()
                }
            }
        }
    }

    // endregion

    // region Вспомогательное --------------------------------------------------

    private fun onEdt(task: () -> Unit) = ApplicationManager.getApplication().invokeLater(task)

    private fun setBusy(value: Boolean) {
        busy = value
    }

    // endregion

    // region Рендеринг --------------------------------------------------------

    /** Дерево: каталог со счётчиком, файл со статусом; просмотренные — приглушённые и не жирные. */
    private inner class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val payload = node.userObject) {
                is DirNode -> {
                    icon = AllIcons.Nodes.Folder
                    val suffix = if (payload.reviewedCount == payload.fileCount) {
                        " · ${payload.fileCount}"
                    } else {
                        " · ${payload.reviewedCount}/${payload.fileCount}"
                    }
                    append(fit(payload.name, tree, node, suffix), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(suffix, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = payload.name
                }
                is FileNode -> {
                    val item = payload.item
                    icon = FileTypeManager.getInstance().getFileTypeByFileName(item.name).icon
                    val status = "${item.status} "
                    append(status, statusAttributes(item.status))
                    val text = if (item.isTodoItem) item.displayPath else item.name
                    append(
                        fit(text, tree, node, status),
                        when {
                            item.isUnchanged -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                            isReviewed(item) -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                            else -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                        }
                    )
                    item.todoText?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    toolTipText = item.path
                }
            }
        }

        /**
         * Укорачивает текст под ширину колонки дерева, обрезая хвост (`Cadwise.ObjectLib.Comm…`).
         *
         * Отступ считаем по уровню узла: `tree.getRowBounds()` здесь звать нельзя —
         * TreeUI для вычисления границ строки снова вызывает этот же рендерер.
         */
        private fun fit(text: String, tree: JTree, node: DefaultMutableTreeNode, companionText: String): String {
            val depth = (node.level - 1).coerceAtLeast(0) // корень скрыт
            val available = tree.width - depth * LEVEL_INDENT - ICON_AND_PADDING
            if (available <= 0) return keepWhole(node, text)
            val metrics = getFontMetrics(font)
            val budget = available - metrics.stringWidth(companionText)
            if (budget <= 0 || metrics.stringWidth(text) <= budget) return keepWhole(node, text)
            truncatedNodes.add(node)

            val ellipsisWidth = metrics.stringWidth(ELLIPSIS)
            var end = text.length - 1
            while (end > 0 &&
                metrics.stringWidth(text.substring(0, end)) + ellipsisWidth > budget
            ) {
                end--
            }
            return text.substring(0, end) + ELLIPSIS
        }

        /** Текст поместился целиком — снимаем пометку, иначе тултип останется от прошлой ширины. */
        private fun keepWhole(node: DefaultMutableTreeNode, text: String): String {
            truncatedNodes.remove(node)
            return text
        }

        private fun statusAttributes(status: String) = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD, statusColor(status)
        )
    }

    private fun statusColor(status: String?): Color = when (status) {
        "A" -> JBColor(Color(60, 140, 80), Color(98, 181, 118))
        "M" -> JBColor(Color(60, 100, 190), Color(110, 160, 240))
        "R" -> JBColor(Color(180, 70, 70), Color(220, 110, 110))
        "?" -> JBColor(Color(150, 70, 190), Color(190, 130, 220))
        else -> JBColor.GRAY
    }

    // endregion

    private companion object {
        /** Шаблон `hg log` для строки состояния. В companion — refresh() стартует прямо из конструктора. */
        const val template = "{branch}|{node|short}|{desc|firstline}"

        const val ELLIPSIS = "…"

        const val NEW_LINE = '\n'.code.toByte()
        const val CARRIAGE_RETURN = '\r'.code.toByte()
        const val PLUS = '+'.code.toByte()
        const val MINUS = '-'.code.toByte()
        val DIFF_GIT_PREFIX = "diff --git ".toByteArray(Charsets.US_ASCII)
        val PLUS_HEADER = "+++ ".toByteArray(Charsets.US_ASCII)
        val MINUS_HEADER = "--- ".toByteArray(Charsets.US_ASCII)
        val B_SLASH = " b/".toByteArray(Charsets.US_ASCII)

        /** Иконка узла плюс отступы, которые рендерер занимает помимо текста. */
        const val ICON_AND_PADDING = 26

        /** Отступ одного уровня вложенности в дереве. */
        const val LEVEL_INDENT = 20

        const val COL_TREE = 0
        const val COL_STATS = 1
        const val COL_EYE = 2

        const val EYE_TOOLTIP = "Отметить просмотренным (клик или Space)"

        val EYE_ICON: Icon = AllIcons.Actions.Show
        val EYE_ICON_FADED: Icon = IconLoader.getTransparentIcon(AllIcons.Actions.Show, 0.25f)

        val ADDED_COLOR = JBColor(Color(40, 130, 70), Color(98, 181, 118))
        val REMOVED_COLOR = JBColor(Color(180, 70, 70), Color(220, 110, 110))
    }
}
