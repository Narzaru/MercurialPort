package com.narzaru.mercurial.changes

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.CheckBoxList
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ToolTipManager

class RevisionsBar(
    private val onToggle: (List<HgRevision>, Boolean) -> Unit,
    private val onReset: () -> Unit
) : JPanel(BorderLayout()) {

    private val header = JBLabel(" ")
    private val list = RevisionsList()
    private val scroll = JBScrollPane(list)
    private val resetLink = ActionLink("Reset") { onReset() }

    private var shownNodes: List<String> = emptyList()

    init {
        header.componentStyle = UIUtil.ComponentStyle.SMALL
        resetLink.toolTipText = "Review the branch's own revisions and skip the merges"

        val top = JPanel(BorderLayout(8, 0))
        top.border = JBUI.Borders.empty(2, 4, 2, 4)
        top.add(header, BorderLayout.CENTER)
        top.add(resetLink, BorderLayout.EAST)

        list.border = JBUI.Borders.empty(0, 4, 2, 4)
        scroll.border = JBUI.Borders.empty()
        scroll.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER

        border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
        add(top, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    fun update(revisions: List<HgRevision>, selection: RevisionSelection) {
        val nodes = revisions.map { it.node }
        if (nodes != shownNodes) {
            shownNodes = nodes
            list.clear()
            for (revision in revisions.asReversed()) {
                list.addItem(revision, BranchRevisions.chipText(revision), selection.isSelected(revision))
            }
        } else {
            applySelection(revisions, selection)
        }
        updateHeader(revisions, selection)
        list.refreshRowHeight()
        val rows = revisions.size.coerceIn(1, VISIBLE_ROWS)
        scroll.preferredSize = Dimension(0, rows * list.fixedCellHeight + JBUI.scale(4))
        revalidate()
        repaint()
    }

    fun applySelection(revisions: List<HgRevision>, selection: RevisionSelection) {
        val byNode = revisions.associateBy { it.node }
        for (row in 0 until list.model.size) {
            val shown = list.getItemAt(row) ?: continue
            val revision = byNode[shown.node]
            list.setItemSelected(shown, revision != null && selection.isSelected(revision))
        }
        list.repaint()
    }

    fun updateHeader(revisions: List<HgRevision>, selection: RevisionSelection) {
        header.text = BranchRevisions.header(revisions, selection)
        resetLink.isVisible = revisions.isNotEmpty() && !selection.isDefaultFor(revisions)
    }

    private inner class RevisionsList : CheckBoxList<HgRevision>() {

        private var nodesBeforeGesture: List<String> = emptyList()
        private var gestureUsedModifiers = false
        private var toggledItem: HgRevision? = null
        private var toggledValue = false

        init {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            fixedCellHeight = JBUI.scale(ROW_HEIGHT)
            setExpandableItemsEnabled(false)
            setCheckBoxListListener { row, value ->
                toggledItem = getItemAt(row)
                toggledValue = value
            }
            ToolTipManager.sharedInstance().registerComponent(this)
        }

        fun refreshRowHeight() {
            val renderer = cellRenderer ?: return
            if (model.size == 0) return
            val cell = renderer.getListCellRendererComponent(this, model.getElementAt(0), 0, false, false)
            fixedCellHeight = maxOf(cell.preferredSize.height, JBUI.scale(ROW_HEIGHT))
        }

        override fun processMouseEvent(event: MouseEvent) {
            if (event.id == MouseEvent.MOUSE_PRESSED) {
                nodesBeforeGesture = selectedNodes()
                gestureUsedModifiers = ClickGesture.usesSelectionModifiers(event.modifiersEx)
            }
            toggledItem = null
            super.processMouseEvent(event)
            if (event.button != MouseEvent.BUTTON1 || event.clickCount > 1) undoToggle() else fireToggle()
        }

        override fun processKeyEvent(event: KeyEvent) {
            nodesBeforeGesture = selectedNodes()
            gestureUsedModifiers = ClickGesture.usesSelectionModifiers(event.modifiersEx)
            toggledItem = null
            super.processKeyEvent(event)
            fireToggle()
        }

        override fun getToolTipText(event: MouseEvent): String? {
            val revision = revisionAt(event) ?: return null
            val text = StringUtil.escapeXmlEntities(BranchRevisions.tooltip(revision)).replace("\n", "<br>")
            return "<html>$text</html>"
        }

        override fun getToolTipLocation(event: MouseEvent): Point? {
            if (revisionAt(event) == null) return null
            val row = locationToIndex(event.point)
            val cell = getCellBounds(row, row) ?: return null
            return Point(0, cell.y + cell.height)
        }

        private fun revisionAt(event: MouseEvent): HgRevision? {
            val row = locationToIndex(event.point)
            if (row < 0 || getCellBounds(row, row)?.contains(event.point) != true) return null
            return getItemAt(row)
        }

        private fun selectedNodes(): List<String> = selectedIndices.toList().mapNotNull { getItemAt(it)?.node }

        private fun shownNodes(): List<String> = (0 until model.size).mapNotNull { getItemAt(it)?.node }

        private fun rowsOf(nodes: Collection<String>): IntArray {
            val wanted = nodes.toSet()
            return (0 until model.size).filter { getItemAt(it)?.node in wanted }.toIntArray()
        }

        private fun undoToggle() {
            val item = toggledItem ?: return
            toggledItem = null
            setItemSelected(item, !toggledValue)
            repaint()
        }

        private fun fireToggle() {
            val item = toggledItem ?: return
            toggledItem = null
            val shown = shownNodes()
            val toggle = RevisionToggling.targets(shown, item.node, nodesBeforeGesture, selectedNodes(), gestureUsedModifiers)
            if (toggle.nodes.isEmpty()) return
            if (toggle.restoresSelection) selectedIndices = rowsOf(toggle.nodes)
            val picked = toggle.nodes.mapNotNull { node -> shownRevision(node) }
            onToggle(picked, toggledValue)
        }

        private fun shownRevision(node: String): HgRevision? =
            (0 until model.size).firstNotNullOfOrNull { getItemAt(it)?.takeIf { item -> item.node == node } }
    }

    private companion object {
        const val VISIBLE_ROWS = 6
        const val ROW_HEIGHT = 22
    }
}
