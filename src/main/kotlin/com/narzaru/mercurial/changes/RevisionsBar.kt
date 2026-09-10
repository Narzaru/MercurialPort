package com.narzaru.mercurial.changes

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JPanel

class RevisionsBar(
    private val onToggle: (HgRevision, Boolean) -> Unit,
    private val onReset: () -> Unit
) : JPanel(BorderLayout()) {

    private val header = JBLabel(" ")
    private val list = JPanel()
    private val scroll = JBScrollPane(list)
    private val resetLink = ActionLink("Reset") { onReset() }

    private val boxes = LinkedHashMap<String, JBCheckBox>()
    private var shownNodes: List<String> = emptyList()

    init {
        header.componentStyle = UIUtil.ComponentStyle.SMALL
        resetLink.toolTipText = "Review the branch's own revisions and skip the merges"

        val top = JPanel(BorderLayout(8, 0))
        top.border = JBUI.Borders.empty(2, 4, 2, 4)
        top.add(header, BorderLayout.CENTER)
        top.add(resetLink, BorderLayout.EAST)

        list.layout = BoxLayout(list, BoxLayout.Y_AXIS)
        list.border = JBUI.Borders.empty(0, 4, 2, 4)
        scroll.border = JBUI.Borders.empty()
        scroll.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER

        border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
        add(top, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    fun update(revisions: List<HgRevision>, selection: RevisionSelection) {
        if (revisions.map { it.node } != shownNodes) {
            shownNodes = revisions.map { it.node }
            boxes.clear()
            list.removeAll()
            for (revision in revisions.asReversed()) {
                val box = JBCheckBox(BranchRevisions.chipText(revision), selection.isSelected(revision))
                box.toolTipText = "<html>" +
                    StringUtil.escapeXmlEntities(BranchRevisions.tooltip(revision)).replace("\n", "<br>") +
                    "</html>"
                box.isOpaque = false
                box.alignmentX = LEFT_ALIGNMENT
                box.addActionListener { onToggle(revision, box.isSelected) }
                boxes[revision.node] = box
                list.add(box)
            }
        } else {
            for (revision in revisions) boxes[revision.node]?.isSelected = selection.isSelected(revision)
        }
        updateHeader(revisions, selection)
        val rows = revisions.size.coerceIn(1, VISIBLE_ROWS)
        scroll.preferredSize = Dimension(0, rows * JBUI.scale(ROW_HEIGHT) + JBUI.scale(4))
        revalidate()
        repaint()
    }

    fun updateHeader(revisions: List<HgRevision>, selection: RevisionSelection) {
        header.text = BranchRevisions.header(revisions, selection)
        resetLink.isVisible = revisions.isNotEmpty() && !selection.isDefaultFor(revisions)
    }

    private companion object {
        const val VISIBLE_ROWS = 6
        const val ROW_HEIGHT = 22
    }
}
