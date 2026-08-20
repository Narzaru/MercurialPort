package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HgLogParserTest {

    /** Строка лога в формате [HgLogParser.TEMPLATE]. */
    private fun line(rev: String, parent: String, desc: String = "msg") =
        "$rev|abc$rev|Автор|2026-01-01 12:00 +0300|$parent|$desc"

    @Test
    fun `разбирает поля ревизии`() {
        val items = HgLogParser.parse(line("12", "11"), "src/a.kt")

        val item = items.single()
        assertEquals("12", item.revision)
        assertEquals("abc12", item.node)
        assertEquals("Автор", item.author)
        assertEquals("2026-01-01 12:00 +0300", item.date)
        assertEquals("msg", item.message)
        assertEquals("11", item.parentRev)
    }

    @Test
    fun `сообщение с вертикальной чертой не обрезается`() {
        val items = HgLogParser.parse(line("1", "0", desc = "fix a|b|c"), "a.kt")

        assertEquals("fix a|b|c", items.single().message)
    }

    @Test
    fun `путь одинаков у всех ревизий`() {
        // Переименования шаблон не считает — они выясняются по требованию, через hg debugrename.
        val items = HgLogParser.parse("${line("2", "1")}\n${line("1", "0")}", "src/a.kt")

        assertEquals(listOf("src/a.kt", "src/a.kt"), items.map { it.path })
        assertEquals(listOf("src/a.kt", "src/a.kt"), items.map { it.parentPath })
    }

    @Test
    fun `битые строки пропускаются`() {
        val items = HgLogParser.parse("мусор без разделителей\n${line("1", "0")}\n", "a.kt")

        assertEquals(1, items.size)
    }

    @Test
    fun `путь с обратными слэшами нормализуется`() {
        val items = HgLogParser.parse(line("1", "0"), "src\\a.kt")

        assertEquals("src/a.kt", items.single().path)
    }

    @Test
    fun `пустой вывод даёт пустой список`() {
        assertTrue(HgLogParser.parse("", "a.kt").isEmpty())
    }
}
