package com.github.narzaru.hgrider.changes

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/**
 * FlowLayout, который умеет считать свою высоту по фактической ширине контейнера.
 *
 * Штатный FlowLayout всегда рапортует preferredSize как одну строку, поэтому в узком
 * тул-окне (вертикальный док) лишние компоненты не переносятся, а просто обрезаются.
 * Здесь preferred/minimum size считается с учётом переносов, и панель получает
 * настоящую высоту в несколько строк.
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 2) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, false).also { it.width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val targetWidth = availableWidth(target)
            val insets = target.insets
            val horizontalInsets = insets.left + insets.right + hgap * 2
            val maxWidth = targetWidth - horizontalInsets

            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            for (i in 0 until target.componentCount) {
                val component = target.getComponent(i)
                if (!component.isVisible) continue
                val size = if (preferred) component.preferredSize else component.minimumSize
                if (rowWidth + size.width > maxWidth && rowWidth > 0) {
                    addRow(dim, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth > 0) rowWidth += hgap
                rowWidth += size.width
                rowHeight = maxOf(rowHeight, size.height)
            }
            addRow(dim, rowWidth, rowHeight)

            dim.width += horizontalInsets
            dim.height += insets.top + insets.bottom + vgap * 2
            return dim
        }
    }

    /** Ширина, на которую реально можно рассчитывать: у самого контейнера её может ещё не быть. */
    private fun availableWidth(target: Container): Int {
        var container: Container? = target
        while (container != null && container.size.width == 0 && container.parent != null) {
            container = container.parent
        }
        val width = container?.size?.width ?: 0
        return if (width == 0) Int.MAX_VALUE else width
    }

    private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
        dim.width = maxOf(dim.width, rowWidth)
        if (dim.height > 0) dim.height += vgap
        dim.height += rowHeight
    }
}
