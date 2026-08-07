package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Рендереры дерева изменений. Каждый переиспользует один компонент: на дереве в тысячи
 * строк создание нового Swing-компонента на каждую ячейку заметно тормозит отрисовку —
 * поэтому и тултипы выставляются не здесь, а в `getToolTipText` самой таблицы.
 */

/** Колонка `+N −M`, выровненная по правому краю. */
class StatsCellRenderer(private val nodeAt: (Int) -> DefaultMutableTreeNode?) : TableCellRenderer {

    private val label = SimpleColoredComponent().apply { isOpaque = true }

    /** Прижимает счётчик к правому краю колонки. */
    private val holder = JPanel(BorderLayout()).apply {
        isOpaque = true
        add(label, BorderLayout.EAST)
    }

    override fun getTableCellRendererComponent(
        table: javax.swing.JTable, value: Any?, isSelected: Boolean,
        hasFocus: Boolean, row: Int, column: Int
    ): java.awt.Component {
        label.clear()
        val background = if (isSelected) table.selectionBackground else table.background
        label.background = background
        holder.background = background
        val (added, removed) = when (val payload = nodeAt(row)?.userObject) {
            is FileNode -> payload.item.added to payload.item.removed
            is DirNode -> payload.added to payload.removed
            else -> 0 to 0
        }
        if (added > 0) {
            label.append("+$added ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ADDED_COLOR))
        }
        if (removed > 0) {
            label.append("−$removed", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, REMOVED_COLOR))
        }
        return holder
    }

    private companion object {
        val ADDED_COLOR = JBColor(Color(40, 130, 70), Color(98, 181, 118))
        val REMOVED_COLOR = JBColor(Color(180, 70, 70), Color(220, 110, 110))
    }
}

/** Колонка отметки просмотра: у каталога «глаз» горит, только когда просмотрено всё внутри. */
class EyeCellRenderer(
    private val nodeAt: (Int) -> DefaultMutableTreeNode?,
    private val allReviewed: (DefaultMutableTreeNode) -> Boolean
) : TableCellRenderer {

    private val label = JBLabel().apply {
        isOpaque = true
        horizontalAlignment = SwingConstants.CENTER
        border = BorderFactory.createEmptyBorder()
        toolTipText = TOOLTIP
    }

    override fun getTableCellRendererComponent(
        table: javax.swing.JTable, value: Any?, isSelected: Boolean,
        hasFocus: Boolean, row: Int, column: Int
    ): java.awt.Component {
        label.background = if (isSelected) table.selectionBackground else table.background
        val node = nodeAt(row)
        label.icon = when {
            node == null -> null
            allReviewed(node) -> EYE_ICON
            else -> EYE_ICON_FADED
        }
        return label
    }

    companion object {
        const val TOOLTIP = "Отметить просмотренным (клик или Space)"
        private val EYE_ICON: Icon = AllIcons.Actions.Show
        private val EYE_ICON_FADED: Icon = IconLoader.getTransparentIcon(AllIcons.Actions.Show, 0.25f)
    }
}

/**
 * Дерево: каталог со счётчиком просмотренных, файл со статусом. Непросмотренные файлы
 * жирные, просмотренные и не имеющие реальных изменений — серые и обычным начертанием.
 *
 * [markTruncated] сообщает панели, у каких узлов текст не поместился: тултип показываем
 * только для них, дубликат полного текста на каждой строке только мешает.
 */
class ChangesNodeRenderer(
    private val isReviewed: (HgFileItem) -> Boolean,
    private val markTruncated: (DefaultMutableTreeNode, Boolean) -> Unit
) : ColoredTreeCellRenderer() {

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
            }
            is FileNode -> {
                val item = payload.item
                icon = FileTypeManager.getInstance().getFileTypeByFileName(item.name).icon
                val status = "${item.status} "
                append(status, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, statusColor(item.status)))
                val text = if (item.isTodoItem) item.displayPath else item.name
                append(
                    fit(text, tree, node, status),
                    when {
                        // Просмотренные и «без изменений» гасим одинаково: и то, и другое —
                        // строки, до которых больше нет дела.
                        item.isUnchanged || isReviewed(item) -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        else -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    }
                )
                item.todoText?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        }
    }

    /**
     * Отступ считаем по уровню узла: `tree.getRowBounds()` здесь звать нельзя —
     * TreeUI для вычисления границ строки снова вызывает этот же рендерер, получается
     * бесконечная рекурсия и StackOverflowError прямо в EDT.
     */
    private fun fit(text: String, tree: JTree, node: DefaultMutableTreeNode, companionText: String): String {
        val depth = (node.level - 1).coerceAtLeast(0) // корень скрыт
        val available = tree.width - depth * LEVEL_INDENT - ICON_AND_PADDING
        if (available <= 0) {
            markTruncated(node, false)
            return text
        }
        val metrics = getFontMetrics(font)
        val budget = available - metrics.stringWidth(companionText)
        val fitted = TextFitter.fit(text, budget) { metrics.stringWidth(it) }
        // Снимаем пометку и когда текст поместился, иначе тултип останется от прошлой ширины.
        markTruncated(node, fitted.truncated)
        return fitted.text
    }

    private companion object {
        /** Иконка узла плюс отступы, которые рендерер занимает помимо текста. */
        const val ICON_AND_PADDING = 26

        /** Отступ одного уровня вложенности в дереве. */
        const val LEVEL_INDENT = 20
    }
}

/** Цвет буквы статуса `hg status`. */
fun statusColor(status: String?): Color = when (status) {
    "A" -> JBColor(Color(60, 140, 80), Color(98, 181, 118))
    "M" -> JBColor(Color(60, 100, 190), Color(110, 160, 240))
    // `!` — то же удаление, что и `R`, только сделанное мимо hg: цвет общий.
    "R", "!" -> JBColor(Color(180, 70, 70), Color(220, 110, 110))
    "?" -> JBColor(Color(150, 70, 190), Color(190, 130, 220))
    else -> JBColor.GRAY
}
