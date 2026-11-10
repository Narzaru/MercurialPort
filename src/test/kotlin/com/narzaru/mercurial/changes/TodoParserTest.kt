package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgFileStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoParserTest {

    private val source = HgFileItem(status = HgFileStatus.MODIFIED, path = "src/a.kt")

    private fun parse(text: String) = TodoParser.parse(source, text)

    @Test
    fun `finds a TODO in a line comment`() {
        val items = parse("val x = 1\n// TODO: убрать хак\nval y = 2")

        assertEquals(1, items.size)
        assertEquals(2, items.single().lineNumber)
        assertEquals("убрать хак", items.single().text)
    }

    @Test
    fun `finds a TODO in a block comment`() {
        val items = parse("/* TODO подумать */")

        assertEquals("подумать", items.single().text)
    }

    @Test
    fun `finds a TODO inside a multiline block`() {
        val items = parse("/*\n * TODO дописать\n */\ncode()")

        assertEquals(1, items.size)
        assertEquals(2, items.single().lineNumber)
        assertEquals("дописать", items.single().text)
    }

    @Test
    fun `the case of the word does not matter`() {
        assertEquals(1, parse("// todo раз").size)
        assertEquals(1, parse("// ToDo два").size)
        assertEquals(1, parse("// TODO три").size)
    }

    @Test
    fun `a TODO outside a comment does not count`() {
        assertTrue(parse("val todo = \"нет\"").isEmpty())
    }

    @Test
    fun `after a block is closed the code is not a comment again`() {
        assertTrue(parse("/* коммент */ val todo = 1").isEmpty())
    }

    @Test
    fun `several TODOs give several items with line numbers`() {
        val items = parse("// TODO раз\ncode()\n// TODO два")

        assertEquals(listOf(1, 3), items.map { it.lineNumber })
        assertEquals(listOf("раз", "два"), items.map { it.text })
    }

    @Test
    fun `an item inherits the status and the path of its file`() {
        val item = parse("// TODO раз").single()

        assertEquals(source.path, item.file.path)
        assertEquals(source.status, item.file.status)
    }

    @Test
    fun `an item keeps whether its file is unchanged but not its counts, copy source or revisions`() {
        val counted = source.copy(
            status = HgFileStatus.UNCHANGED, isUnchanged = true, added = 3, removed = 1,
            copiedFrom = "src/old.kt", baseRev = "7", headRev = "9"
        )

        val item = TodoParser.parse(counted, "// TODO раз").single()

        assertEquals(
            HgFileItem(status = HgFileStatus.UNCHANGED, path = "src/a.kt", isUnchanged = true),
            item.file
        )
    }

    @Test
    fun `an item is shown as the file name and its line`() {
        val item = parse("code()\n// TODO раз").single()

        assertEquals("a.kt:2", item.displayPath)
    }

    @Test
    fun `separators after the word are dropped`() {
        assertEquals("текст", parse("// TODO: текст").single().text)
        assertEquals("текст", parse("// TODO - текст").single().text)
        assertEquals("текст", parse("//TODO текст").single().text)
    }

    @Test
    fun `a TODO after code at the end of a line is found`() {
        val items = parse("val x = 1 // TODO проверить")

        assertEquals("проверить", items.single().text)
    }

    @Test
    fun `a bare TODO without text keeps the comment line`() {
        assertEquals("// TODO", parse("// TODO").single().text)
    }

    @Test
    fun `a binary file is not parsed`() {
        assertTrue(parse("\u0000// TODO раз").isEmpty())
    }

    @Test
    fun `a file without the word is not parsed`() {
        assertTrue(parse("// обычный комментарий\ncode()").isEmpty())
    }

    @Test
    fun `CRLF does not shift the line numbers`() {
        val items = parse("code()\r\n// TODO раз\r\ncode()")

        assertEquals(2, items.single().lineNumber)
    }
}
