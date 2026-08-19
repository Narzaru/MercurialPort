package com.narzaru.mercurial.changes

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * Иконки, которых в платформе нет: перечёркнутый глаз и точка отметки. Рисуются сами —
 * подходящей пары «пустая/залитая точка» в `AllIcons` нет, а перечёркнутого глаза нет вовсе
 * (`Actions.ToggleVisibility` — глаз с пунктиром, а не со штрихом).
 */
object HgIcons {

    /** «Показывать то, что обычно скрыто» — неотслеживаемые файлы. */
    val EYE_CROSSED: Icon = SlashedIcon(AllIcons.General.Show)

    /** Файл просмотрен. */
    val DOT_FILLED: Icon = DotIcon(filled = true)

    /** Файл ещё не просмотрен. */
    val DOT_EMPTY: Icon = DotIcon(filled = false)
}

/** Иконка с диагональным штрихом поверх. Штрих обводится фоном, иначе он теряется в рисунке. */
private class SlashedIcon(private val base: Icon) : Icon {

    override fun getIconWidth() = base.iconWidth
    override fun getIconHeight() = base.iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        base.paintIcon(c, g, x, y)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val inset = JBUI.scale(2)
            val x1 = x + inset
            val y1 = y + iconHeight - inset
            val x2 = x + iconWidth - inset
            val y2 = y + inset

            g2.color = c?.background ?: JBColor.background()
            g2.stroke = BasicStroke(JBUI.scale(3).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawLine(x1, y1, x2, y2)

            g2.color = STROKE_COLOR
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawLine(x1, y1, x2, y2)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        val STROKE_COLOR = JBColor(Color(0x6C707E), Color(0xCED0D6))
    }
}

/** Точка отметки: залитая — просмотрено, пустая — нет. */
private class DotIcon(private val filled: Boolean) : Icon {

    override fun getIconWidth() = JBUI.scale(SIZE)
    override fun getIconHeight() = JBUI.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val d = JBUI.scale(DIAMETER)
            val dx = x + (iconWidth - d) / 2
            val dy = y + (iconHeight - d) / 2
            if (filled) {
                g2.color = FILLED_COLOR
                g2.fillOval(dx, dy, d, d)
            } else {
                g2.color = EMPTY_COLOR
                g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
                // Диаметр на пиксель меньше: обводка рисуется по контуру и иначе вылезает за него.
                g2.drawOval(dx, dy, d - 1, d - 1)
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val SIZE = 16
        const val DIAMETER = 12
        val FILLED_COLOR = JBColor(Color(0x3574F0), Color(0x548AF7))
        val EMPTY_COLOR = JBColor(Color(0x9AA0AB), Color(0x6F737A))
    }
}
