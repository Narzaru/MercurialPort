package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFitterTest {

    /** Моноширинный «шрифт»: один символ — одна условная единица ширины. */
    private val monospace: (String) -> Int = { it.length }

    private fun fit(text: String, budget: Int) = TextFitter.fit(text, budget, monospace)

    @Test
    fun `текст по размеру остаётся целым`() {
        val fitted = fit("abcdef", budget = 6)

        assertEquals("abcdef", fitted.text)
        assertFalse(fitted.truncated)
    }

    @Test
    fun `длинный текст обрезается с многоточием`() {
        val fitted = fit("abcdefghij", budget = 5)

        assertEquals("abcd…", fitted.text)
        assertTrue(fitted.truncated)
        assertEquals(5, fitted.text.length)
    }

    @Test
    fun `обрезанный текст не шире бюджета`() {
        for (budget in 2..12) {
            val fitted = fit("abcdefghijklmnop", budget)

            assertTrue("бюджет $budget", monospace(fitted.text) <= budget)
        }
    }

    @Test
    fun `нулевой и отрицательный бюджет оставляют текст как есть`() {
        // Ширина колонки ещё неизвестна — обрезать наугад хуже, чем показать целиком.
        assertEquals("abc", fit("abc", budget = 0).text)
        assertFalse(fit("abc", budget = 0).truncated)
        assertEquals("abc", fit("abc", budget = -10).text)
    }

    @Test
    fun `пустой текст не ломается`() {
        assertEquals("", fit("", budget = 5).text)
    }
}
