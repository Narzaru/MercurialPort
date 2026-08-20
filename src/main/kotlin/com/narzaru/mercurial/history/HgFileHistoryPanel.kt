package com.narzaru.mercurial.history

import com.narzaru.mercurial.diff.HgDiffTabManager
import com.narzaru.mercurial.hg.HgCommandRunner
import com.narzaru.mercurial.hg.HgLogParser
import com.narzaru.mercurial.hg.HgOutputDecoder
import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.hg.HgRenameParser
import com.narzaru.mercurial.model.HgHistoryItem
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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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
import java.io.File
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.MouseInputAdapter
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Окно истории файла: `hg log -f`, открытие ревизии, дифф выбранных ревизий.
 */
class HgFileHistoryPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private var targetFile: File? = null
    private var repoRoot: File? = null

    /**
     * Открытие/закрытие вкладок нами самими меняет выбор в редакторе. Без этого флага
     * панель уходила на соседний файл, перезагружала список и сбрасывала выделение —
     * следующий `Open Diff` уже не находил выбранной ревизии.
     */
    private var followSuppressed = false

    private val titleLabel = JBLabel("No file selected")
    private val statusLabel = JBLabel("Ready")
    private val tableModel = HistoryTableModel()
    private val table = JBTable(tableModel)

    /** Быстрые клики по списку: показываем только результат последнего запроса. */
    private var diffRequestId = 0

    /** То же для `hg log`: ответ по прошлому файлу не должен затирать текущий список. */
    private var historyRequestId = 0

    /** Причина последнего неудачного `hg cat` — чтобы показать её вместо молчания. */
    @Volatile
    private var lastCatError: String? = null

    /**
     * Ответы `hg debugrename`: ключ «ревизия|путь», значение — старое имя либо пустая строка,
     * если переименования не было. Спрашивают из фоновых потоков, каждый ответ стоит запуска `hg`.
     */
    private val renameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Гасит череду `hg log` при быстром переключении вкладок редактора. */
    private val followAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        buildUi()
        followActiveEditor()
        currentEditorFile()?.let { loadHistory(it.path) }
    }

    override fun dispose() = Unit

    // region Привязка к редактору ---------------------------------------------

    private fun currentEditorFile(): VirtualFile? =
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.takeIf { !it.isDirectory }

    /**
     * Панель сама показывает историю файла, открытого в редакторе: иначе окно
     * остаётся пустым, пока историю не запросят откуда-то ещё.
     */
    private fun followActiveEditor() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile?.takeIf { !it.isDirectory } ?: return
                    // Дифф-вкладка — не файл на диске: пойдя за ней, панель перезагружала
                    // историю для несуществующего пути и очищала список.
                    if (!file.isInLocalFileSystem) return
                    requestHistory(file.path, fromEditor = true)
                }
            }
        )
    }

    /**
     * Показать историю файла по запросу извне (редактор, выбор в Hg Changes).
     * Игнорируется, если панель скрыта или уже показывает этот файл.
     */
    fun syncTo(path: String) = requestHistory(path, fromEditor = false)

    /**
     * [fromEditor] отделяет слежение за редактором от явного запроса: гасить (см.
     * [followSuppressed]) можно только первое. Иначе открытие диффа из Hg Changes
     * подавляло и сам запрос истории — панель переставала реагировать на клики.
     */
    private fun requestHistory(path: String, fromEditor: Boolean) {
        if (fromEditor && followSuppressed) return
        // Не дёргаем hg, пока окно скрыто.
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow?.isVisible != true) return
        val normalized = File(path).absolutePath
        if (targetFile?.absolutePath.equals(normalized, ignoreCase = true)) return
        if (!fromEditor) {
            loadHistory(path)
            return
        }
        // По вкладкам редактора ходят насквозь, а каждый `hg log` — это отдельный процесс:
        // ждём остановки, иначе на промежуточные файлы уходит по запуску Mercurial.
        followAlarm.cancelAllRequests()
        followAlarm.addRequest({ loadHistory(path) }, FOLLOW_DEBOUNCE_MS)
    }

    // endregion

    private fun buildUi() {
        val north = JPanel()
        north.layout = BoxLayout(north, BoxLayout.Y_AXIS)
        north.add(buildToolbar())
        north.add(buildInfoRow())
        // BoxLayout по умолчанию центрирует компоненты разной ширины — как в Hg Changes
        // выравниваем всё по левому краю, иначе тулбар и подписи «плавают».
        for (i in 0 until north.componentCount) {
            (north.getComponent(i) as? JComponent)?.alignmentX = LEFT_ALIGNMENT
        }
        add(north, BorderLayout.NORTH)

        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        // Любой клик по строке — дифф. Двойной здесь не задействован: открытие самой ревизии
        // файла интереса не представляет, а второй клик иначе показывал бы тот же дифф.
        table.addMouseListener(object : MouseInputAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.button != java.awt.event.MouseEvent.BUTTON1) return
                if (table.rowAtPoint(e.point) < 0) return
                if (e.clickCount == 1) diffSelected()
            }
        })
        configureDateColumn()
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    /**
     * Дата: в колонке — только день, время и часовой пояс уходят в тултип. Колонка от этого
     * заметно уже, а место нужно колонке сообщения.
     */
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
                // JTable спрашивает подсказку у компонента, отрисовавшего ячейку под курсором,
                // так что переиспользуемый рендерер здесь безопасен.
                toolTipText = HistoryDateText.full(raw)
                return this
            }
        }
        column.preferredWidth = JBUI.scale(DATE_COLUMN_WIDTH)
        column.maxWidth = JBUI.scale(DATE_COLUMN_MAX_WIDTH)
    }

    /** Тулбар в стиле Hg Changes: компактные иконки вместо ряда подписанных кнопок. */
    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup()
        group.add(action("Refresh", "Reload the file history", AllIcons.Actions.Refresh,
            { targetFile != null }) { reload() })
        group.add(Separator.getInstance())
        // Дифф по одинарному клику — основной путь (контракт UI), но выделить две ревизии
        // можно и с клавиатуры (Shift+стрелки), поэтому действие оставлено и в тулбаре.
        group.add(action("Show Diff", "Diff the selected revision (or two revisions against each other)",
            AllIcons.Actions.Diff, { table.selectedRowCount in 1..2 }) { diffSelected() })
        group.add(action("Open", "Open the selected revision of the file as a temporary copy",
            AllIcons.Actions.OpenNewTab, { table.selectedRowCount > 0 }) { openSelected() })

        val toolbar = ActionManager.getInstance().createActionToolbar("HgFileHistory", group, true)
        toolbar.targetComponent = this
        toolbar.setLayoutPolicy(ActionToolbar.WRAP_LAYOUT_POLICY)
        return toolbar.component
    }

    /** Заголовок и статус — одной компактной строкой, как сводка в Hg Changes. */
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

    fun loadHistory(path: String) {
        targetFile = File(path)
        titleLabel.text = "History: ${targetFile!!.name}"
        reload()
    }

    private fun reload() {
        val file = targetFile ?: return
        statusLabel.text = "Loading history..."
        // Список не очищаем: старые строки живут до прихода новых, иначе панель моргает
        // на каждой перезагрузке. Устаревшие ответы отсекаем счётчиком поколений.
        val requestId = ++historyRequestId
        ApplicationManager.getApplication().executeOnPooledThread {
            val root = HgCommandRunner.findRepoRoot(file.parentFile)
            if (root == null) {
                onEdt {
                    if (requestId != historyRequestId) return@onEdt
                    statusLabel.text = "No repository found."
                    tableModel.setItems(emptyList())
                }
                return@executeOnPooledThread
            }
            repoRoot = root
            val rel = HgPaths.relativize(file, root)
            val runner = HgCommandRunner(root)
            val res = runner.run("log", "-f", rel, "--template", "${HgLogParser.TEMPLATE}\n")
            val items = if (res.success) HgLogParser.parse(res.stdout, rel) else emptyList()
            onEdt {
                if (requestId != historyRequestId) return@onEdt // пришёл более свежий запрос
                if (!res.success && items.isEmpty()) {
                    tableModel.setItems(emptyList())
                    statusLabel.text = "HG Error: ${res.stderr.trim()}"
                } else {
                    tableModel.setItems(items)
                    statusLabel.text = "Loaded ${items.size} commits."
                }
            }
        }
    }

    private fun selectedItems(): List<HgHistoryItem> =
        table.selectedRows.toList().mapNotNull { tableModel.itemAt(it) }

    private fun openSelected() {
        val item = selectedItems().firstOrNull() ?: return
        val root = repoRoot ?: return
        val file = targetFile ?: return
        val rel = item.path.ifBlank { HgPaths.relativize(file, root) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val tmp = extractFile(root, rel, item, "open") ?: return@executeOnPooledThread
            onEdt {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp) ?: return@onEdt
                // Извлечённая копия лежит во временном каталоге и репозитория не имеет —
                // панель не должна переключаться на неё.
                withoutFollow { FileEditorManager.getInstance(project).openFile(vf, true) }
            }
        }
    }

    private fun diffSelected() {
        val root = repoRoot ?: return
        val file = targetFile ?: return
        val selected = selectedItems()
        if (selected.isEmpty()) return
        if (selected.size > 2) {
            Messages.showWarningDialog(project, "Select one or two revisions to compare.", "Hg Diff")
            return
        }

        val requestId = ++diffRequestId
        lastCatError = null
        ApplicationManager.getApplication().executeOnPooledThread {
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
            var leftContent: String? = null
            var rightContent: String? = null
            var leftLabel: String
            val rightLabel: String
            val key: String

            if (selected.size == 2) {
                val idx1 = tableModel.indexOf(selected[0])
                val idx2 = tableModel.indexOf(selected[1])
                val newer = if (idx1 < idx2) selected[0] else selected[1]
                val older = if (idx1 < idx2) selected[1] else selected[0]
                val newerRow = minOf(idx1, idx2)
                val olderRow = maxOf(idx1, idx2)
                leftContent = catFollowingRenames(root, older.path, older.revision, olderRow).text
                rightContent = catFollowingRenames(root, newer.path, newer.revision, newerRow).text
                leftLabel = revLabel(older)
                rightLabel = revLabel(newer)
                key = "sel|${older.revision}|${newer.revision}"
            } else {
                // Одна ревизия — показываем, что изменила она сама: сравниваем с её первым
                // родителем, а не со следующей строкой списка (при слияниях это разные
                // ревизии). Имя у родителя может быть старым — его ищет catFollowingRenames.
                val item = selected[0]
                val row = tableModel.indexOf(item)
                val parent = item.parentRev.toIntOrNull() ?: -1
                val right = catFollowingRenames(root, item.path, item.revision, row)
                val left = if (parent < 0) Extracted(null, right.path)
                else catFollowingRenames(root, right.path, item.parentRev, row)
                leftContent = left.text
                rightContent = right.text
                leftLabel = if (parent < 0) "No parent revision" else "Rev ${item.parentRev} (parent)"
                rightLabel = revLabel(item)
                if (left.path != right.path) {
                    leftLabel += " — ${left.path.substringAfterLast('/')}"
                }
                key = "prev|${item.revision}|${item.path}"
            }

            onEdt {
                if (requestId != diffRequestId) return@onEdt // пришёл более свежий клик
                // Иначе падение при открытии вкладки выглядит как «ничего не произошло».
                try {
                    showDiff(file, fileType, leftContent, rightContent, leftLabel, rightLabel, key)
                } catch (e: Exception) {
                    LOG.warn("Could not open the diff ($key)", e)
                    statusLabel.text = "Diff error: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun revLabel(item: HgHistoryItem) = "Rev ${item.revision} (${item.author})"

    private fun showDiff(
        file: File, fileType: FileType,
        leftContent: String?, rightContent: String?,
        leftLabel: String, rightLabel: String, key: String
    ) {
        val factory = DiffContentFactory.getInstance()
        val hasRight = rightContent != null
        if (leftContent == null && !hasRight) {
            statusLabel.text = lastCatError?.let { "Hg Error: $it" } ?: "Nothing to compare for this revision."
            return
        }

        // Появление/удаление файла — это пустая сторона, а не ошибка. В отличие от Hg Changes
        // берём пустой документ, а не createEmpty(): с EmptyContent вкладка для init-коммита
        // (у ревизии нет родителя, слева пусто) не открывалась вовсе.
        val left = factory.create(project, leftContent.orEmpty(), fileType)
        val right = factory.create(project, rightContent.orEmpty(), fileType)
        val leftTitle = if (leftContent == null) "$leftLabel — file added" else leftLabel
        val rightTitle = if (!hasRight) "$rightLabel — file deleted" else rightLabel
        statusLabel.text = "$leftTitle → $rightTitle"

        // Вкладками владеет общий менеджер: иначе Hg Changes и это окно держат каждое свою,
        // и на экране оказывается два диффа сразу.
        val request = SimpleDiffRequest(file.name, left, right, leftTitle, rightTitle)
        project.service<HgDiffTabManager>().show(HgDiffTabManager.OWNER_HISTORY, key, file.name, request)
    }

    /** Содержимое файла в ревизии и имя, под которым его удалось прочитать. */
    private class Extracted(val text: String?, val path: String)

    /**
     * Читает файл в ревизии, доискиваясь старого имени. `hg log` копий не считает — это минуты
     * на длинной истории (см. [HgLogParser]), — поэтому переименование ищется только там, где
     * оно действительно мешает: `hg cat` под текущим именем не нашёл файла.
     *
     * Ревизии просматриваются от [fromRow] к более новым: переименование, записанное в ревизии R,
     * означает, что все её предки знали файл под старым именем. Число запросов ограничено —
     * каждый стоит запуска `hg`, а история переименований длиной в десяток файлов не встречается.
     */
    private fun catFollowingRenames(root: File, path: String, rev: String, fromRow: Int): Extracted {
        var current = path
        catText(root, current, rev)?.let { return Extracted(it, current) }

        var row = fromRow
        var lookups = 0
        while (row >= 0 && lookups < RENAME_LOOKUPS) {
            val at = tableModel.itemAt(row)?.revision ?: break
            lookups++
            val source = renameSource(root, at, current)
            if (source != null && !source.equals(current, ignoreCase = true)) {
                current = source
                catText(root, current, rev)?.let { return Extracted(it, current) }
            }
            row--
        }
        return Extracted(null, current)
    }

    /** Старое имя [path] в ревизии [rev] или `null`. Ответ кэшируется: он стоит запуска `hg`. */
    private fun renameSource(root: File, rev: String, path: String): String? {
        val key = "$rev|${HgPaths.key(path)}"
        renameCache[key]?.let { return it.ifEmpty { null } }
        val res = HgCommandRunner(root).run("debugrename", "-r", rev, path)
        val source = if (res.success) HgRenameParser.sourceOf(res.stdout) else null
        renameCache[key] = source.orEmpty()
        return source
    }

    private fun catText(root: File, rel: String, rev: String): String? {
        val res = HgCommandRunner(root).runToBytesDetailed(listOf("cat", "-r", rev, rel))
        if (res.exitCode == 0) return HgOutputDecoder.decode(res.stdout)
        // Отказ `hg cat` раньше был неотличим от «файла не было в ревизии» — запоминаем причину.
        lastCatError = res.stderr.trim().ifBlank { "hg cat -r $rev $rel: exit ${res.exitCode}" }
        LOG.warn("hg cat -r $rev $rel failed: $lastCatError")
        return null
    }

    private fun extractFile(root: File, rel: String, item: HgHistoryItem, prefix: String): File? {
        val name = File(rel).name
        val dot = name.lastIndexOf('.')
        val base = if (dot >= 0) name.substring(0, dot) else name
        val ext = if (dot >= 0) name.substring(dot) else ""
        val safe = { s: String -> s.replace(Regex("[^A-Za-z0-9._-]"), "_") }
        val tmp = File(
            System.getProperty("java.io.tmpdir"),
            "${safe(base)}_${prefix}_rev${item.revision}_${safe(item.author)}$ext"
        )
        val (exit, bytes) = HgCommandRunner(root).runToBytes(listOf("cat", "-r", item.revision, rel))
        if (exit != 0) return null
        return try {
            tmp.writeBytes(bytes)
            tmp
        } catch (_: Exception) {
            null
        }
    }

    private fun onEdt(task: () -> Unit) = ApplicationManager.getApplication().invokeLater(task)

    /** Выполняет операции с вкладками редактора, не давая панели уйти за сменой выбора. */
    fun withoutFollow(block: () -> Unit) {
        followSuppressed = true
        try {
            block()
        } finally {
            // Снимаем флаг после того, как разойдутся события выбора вкладок.
            onEdt { followSuppressed = false }
        }
    }

    private companion object {
        const val TOOL_WINDOW_ID = "Hg File History"

        /** Пауза перед `hg log` при переходе по вкладкам редактора. */
        const val FOLLOW_DEBOUNCE_MS = 300

        /** Сколько ревизий опросить в поисках старого имени: каждая — отдельный запуск `hg`. */
        const val RENAME_LOOKUPS = 8

        /** Ширины колонки даты: в ней остался только день. */
        const val DATE_COLUMN_WIDTH = 80
        const val DATE_COLUMN_MAX_WIDTH = 110

        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(HgFileHistoryPanel::class.java)
    }

    private class HistoryTableModel : AbstractTableModel() {
        private val columns = arrayOf("Rev", "Node", "Date", "Author", "Message")
        private var rows: List<HgHistoryItem> = emptyList()

        fun setItems(items: List<HgHistoryItem>) {
            rows = items
            fireTableDataChanged()
        }

        companion object {
            const val COL_DATE = 2
        }

        fun itemAt(row: Int): HgHistoryItem? = rows.getOrNull(row)
        fun indexOf(item: HgHistoryItem): Int = rows.indexOf(item)

        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = rows[rowIndex]
            return when (columnIndex) {
                0 -> item.revision
                1 -> item.node
                2 -> item.date
                3 -> item.author
                else -> item.message
            }
        }
    }
}
