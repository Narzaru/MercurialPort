package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HgStatusParserTest {

    @Test
    fun `разбирает статус и путь`() {
        val items = HgStatusParser.parse("M src/main.kt\nA src/new.kt\n")

        assertEquals(listOf("M", "A"), items.map { it.status })
        assertEquals(listOf("src/main.kt", "src/new.kt"), items.map { it.path })
    }

    @Test
    fun `понимает CRLF и пропускает пустые строки`() {
        val items = HgStatusParser.parse("M a.kt\r\n\r\nR b.kt\r\n")

        assertEquals(listOf("a.kt", "b.kt"), items.map { it.path })
    }

    @Test
    fun `сохраняет пробелы внутри пути`() {
        val items = HgStatusParser.parse("M dir with space/file name.kt")

        assertEquals("dir with space/file name.kt", items.single().path)
    }

    @Test
    fun `удалённые мимо hg приходят со статусом восклицания`() {
        val items = HgStatusParser.parse("! gone.kt")

        assertEquals("!", items.single().status)
    }

    @Test
    fun `на пустом выводе список пуст`() {
        assertTrue(HgStatusParser.parse("").isEmpty())
        assertTrue(HgStatusParser.parse("\n\n").isEmpty())
    }

    @Test
    fun `строка с отступом это источник копии предыдущего файла`() {
        val items = HgStatusParser.parse("A src/new.kt\n  src/old.kt\nM other.kt\n")

        assertEquals(listOf("src/new.kt", "other.kt"), items.map { it.path })
        assertEquals("src/old.kt", items[0].copiedFrom)
        assertEquals("", items[1].copiedFrom)
    }

    @Test
    fun `переименование сводится в одну строку`() {
        val items = HgStatusParser.foldRenames(
            HgStatusParser.parse("A src/new.kt\n  src/old.kt\nR src/old.kt\n")
        )

        val item = items.single()
        assertEquals(HgStatusParser.RENAMED_STATUS, item.status)
        assertEquals("src/new.kt", item.path)
        assertEquals("src/old.kt", item.copiedFrom)
    }

    @Test
    fun `копия остаётся добавлением, источник на месте`() {
        val items = HgStatusParser.foldRenames(
            HgStatusParser.parse("A src/copy.kt\n  src/orig.kt\nM src/orig.kt\n")
        )

        assertEquals(listOf("A", "M"), items.map { it.status })
        assertEquals("src/orig.kt", items[0].copiedFrom)
    }

    @Test
    fun `удаление без пары остаётся в списке`() {
        val items = HgStatusParser.foldRenames(HgStatusParser.parse("R src/gone.kt\nA src/new.kt\n"))

        assertEquals(listOf("R", "A"), items.map { it.status })
    }

    @Test
    fun `флаг неотслеживаемых добавляется только по требованию`() {
        assertEquals("-mard", HgStatusParser.statusFlags(includeUntracked = false))
        assertEquals("-mardu", HgStatusParser.statusFlags(includeUntracked = true))
    }
}
