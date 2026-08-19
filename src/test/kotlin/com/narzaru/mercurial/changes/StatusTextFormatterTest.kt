package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTextFormatterTest {

    private val current = "feature|a1b2c3|Добавил фичу"
    private val base = "default|000111|Базовый коммит"

    @Test
    fun `для незакоммиченных показывается только текущая ревизия`() {
        val text = StatusTextFormatter.branchInfo(HgDisplayMode.UNCOMMITTED, current, base)

        assertEquals("Uncommitted in: feature (a1b2c3) \"Добавил фичу\"", text)
    }

    @Test
    fun `для ветки показывается сравнение с родителем`() {
        val text = StatusTextFormatter.branchInfo(HgDisplayMode.BRANCH, current, base)

        assertEquals(
            "Branch: feature (a1b2c3) \"Добавил фичу\" vs Branch point: default (000111) \"Базовый коммит\"",
            text
        )
    }

    @Test
    fun `режим задаёт формулировку сравнения`() {
        assertTrue(
            StatusTextFormatter.branchInfo(HgDisplayMode.BRANCH, current, base)
                .contains(" vs Branch point: ")
        )
        assertTrue(
            StatusTextFormatter.branchInfo(HgDisplayMode.CUSTOM_BRANCH, current, base)
                .contains(" vs Branch: ")
        )
    }

    @Test
    fun `нераспознанное описание ревизии показывается как есть`() {
        val text = StatusTextFormatter.branchInfo(HgDisplayMode.UNCOMMITTED, "мусор", base)

        assertEquals("Uncommitted in: мусор", text)
    }

    @Test
    fun `заголовок коммита с вертикальной чертой не обрезается`() {
        val text = StatusTextFormatter.branchInfo(HgDisplayMode.UNCOMMITTED, "b|node|fix a|b", base)

        assertEquals("Uncommitted in: b (node) \"fix a|b\"", text)
    }

    @Test
    fun `сводка суммирует плюсы и минусы`() {
        val files = listOf(
            HgFileItem(status = "M", path = "a.kt", added = 3, removed = 1),
            HgFileItem(status = "M", path = "b.kt", added = 4, removed = 2)
        )

        val text = StatusTextFormatter.summary(files, reviewed = 1, statsPending = false)

        assertEquals("2 files  +7  −3  ·  1/2 reviewed", text)
    }

    @Test
    fun `пока статистика считается вместо чисел показывается пометка`() {
        val files = listOf(HgFileItem(status = "M", path = "a.kt"))

        val text = StatusTextFormatter.summary(files, reviewed = 0, statsPending = true)

        assertTrue(text.contains("counting ±"))
        assertTrue(text.startsWith("1 files"))
    }

    @Test
    fun `пустой список даёт пустую сводку`() {
        assertEquals(" ", StatusTextFormatter.summary(emptyList(), reviewed = 0, statsPending = false))
        assertEquals(" ", StatusTextFormatter.summary(emptyList(), reviewed = 0, statsPending = true))
    }
}
