package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoParserTest {

    private val source = HgFileItem(status = "M", path = "src/a.kt")

    private fun parse(text: String) = TodoParser.parse(source, text)

    @Test
    fun `находит TODO в строчном комментарии`() {
        val items = parse("val x = 1\n// TODO: убрать хак\nval y = 2")

        assertEquals(1, items.size)
        assertEquals(2, items.single().lineNumber)
        assertEquals("убрать хак", items.single().todoText)
    }

    @Test
    fun `находит TODO в блочном комментарии`() {
        val items = parse("/* TODO подумать */")

        assertEquals("подумать", items.single().todoText)
    }

    @Test
    fun `находит TODO внутри многострочного блока`() {
        val items = parse("/*\n * TODO дописать\n */\ncode()")

        assertEquals(1, items.size)
        assertEquals(2, items.single().lineNumber)
        assertEquals("дописать", items.single().todoText)
    }

    @Test
    fun `регистр слова не важен`() {
        assertEquals(1, parse("// todo раз").size)
        assertEquals(1, parse("// ToDo два").size)
        assertEquals(1, parse("// TODO три").size)
    }

    @Test
    fun `TODO вне комментария не считается`() {
        assertTrue(parse("val todo = \"нет\"").isEmpty())
    }

    @Test
    fun `после закрытия блока код снова не комментарий`() {
        assertTrue(parse("/* коммент */ val todo = 1").isEmpty())
    }

    @Test
    fun `несколько TODO дают несколько элементов с номерами строк`() {
        val items = parse("// TODO раз\ncode()\n// TODO два")

        assertEquals(listOf(1, 3), items.map { it.lineNumber })
        assertEquals(listOf("раз", "два"), items.map { it.todoText })
    }

    @Test
    fun `элемент наследует статус и путь исходного файла`() {
        val item = parse("// TODO раз").single()

        assertEquals(source.path, item.path)
        assertEquals(source.status, item.status)
        assertTrue(item.isTodoItem)
    }

    @Test
    fun `разделители после слова отбрасываются`() {
        assertEquals("текст", parse("// TODO: текст").single().todoText)
        assertEquals("текст", parse("// TODO - текст").single().todoText)
        assertEquals("текст", parse("//TODO текст").single().todoText)
    }

    @Test
    fun `TODO после кода в конце строки находится`() {
        val items = parse("val x = 1 // TODO проверить")

        assertEquals("проверить", items.single().todoText)
    }

    @Test
    fun `голое TODO без текста остаётся строкой комментария`() {
        assertEquals("// TODO", parse("// TODO").single().todoText)
    }

    @Test
    fun `бинарный файл не разбирается`() {
        // Нулевой байт — признак бинарника; «строк» в нём были бы десятки тысяч.
        assertTrue(parse("\u0000// TODO раз").isEmpty())
    }

    @Test
    fun `файл без слова не разбирается`() {
        assertTrue(parse("// обычный комментарий\ncode()").isEmpty())
    }

    @Test
    fun `CRLF не сдвигает номера строк`() {
        val items = parse("code()\r\n// TODO раз\r\ncode()")

        assertEquals(2, items.single().lineNumber)
    }
}
