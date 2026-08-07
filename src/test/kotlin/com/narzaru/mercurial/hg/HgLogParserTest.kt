package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HgLogParserTest {

    /** Строка лога в формате [HgLogParser.TEMPLATE]. */
    private fun line(rev: String, parent: String, copies: String = "", desc: String = "msg") =
        "$rev|abc$rev|Автор|2026-01-01 12:00 +0300|$parent|$copies|$desc"

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
    fun `без переименований путь одинаков у всех ревизий`() {
        val items = HgLogParser.parse("${line("2", "1")}\n${line("1", "0")}", "src/a.kt")

        assertEquals(listOf("src/a.kt", "src/a.kt"), items.map { it.path })
        assertEquals(listOf("src/a.kt", "src/a.kt"), items.map { it.parentPath })
    }

    @Test
    fun `переименование отматывает путь у предков`() {
        // Ревизия 2 переименовала old.kt в new.kt; ревизия 1 старше и знает только old.kt.
        val stdout = "${line("2", "1", copies = "src/old.kt=>src/new.kt;")}\n${line("1", "0")}"

        val items = HgLogParser.parse(stdout, "src/new.kt")

        assertEquals("src/new.kt", items[0].path)
        assertEquals("src/old.kt", items[0].parentPath)
        assertEquals("src/old.kt", items[1].path)
    }

    @Test
    fun `копия чужого файла путь не меняет`() {
        val stdout = line("2", "1", copies = "src/other.kt=>src/copy.kt;")

        val items = HgLogParser.parse(stdout, "src/a.kt")

        assertEquals("src/a.kt", items.single().parentPath)
    }

    @Test
    fun `путь с пробелом разбирается по разделителю копий`() {
        val stdout = line("2", "1", copies = "old name.kt=>new name.kt;")

        val items = HgLogParser.parse(stdout, "new name.kt")

        assertEquals("old name.kt", items.single().parentPath)
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

    @Test
    fun `источник копии ищется без учёта регистра`() {
        assertEquals("Old.kt", HgLogParser.copySourceOf("Old.kt=>New.kt;", "new.kt"))
        assertNull(HgLogParser.copySourceOf("Old.kt=>New.kt;", "third.kt"))
    }
}
