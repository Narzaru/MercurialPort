package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HgDiffStatParserTest {

    private fun parse(diff: String) = HgDiffStatParser.parse(diff.toByteArray(Charsets.UTF_8))

    @Test
    fun `считает добавленные и удалённые строки`() {
        val stats = parse(
            """
            diff --git a/src/main.kt b/src/main.kt
            --- a/src/main.kt
            +++ b/src/main.kt
            @@ -1,3 +1,3 @@
             unchanged
            -old line
            +new line
            +another new
            """.trimIndent()
        )

        assertEquals(2, stats.getValue("src/main.kt").added)
        assertEquals(1, stats.getValue("src/main.kt").removed)
    }

    @Test
    fun `заголовки --- и +++ в счёт не идут`() {
        val stats = parse(
            """
            diff --git a/a.kt b/a.kt
            --- a/a.kt
            +++ b/a.kt
            @@ -0,0 +1 @@
            +one
            """.trimIndent()
        )

        assertEquals(1, stats.getValue("a.kt").added)
        assertEquals(0, stats.getValue("a.kt").removed)
    }

    @Test
    fun `разделяет статистику по файлам`() {
        val stats = parse(
            """
            diff --git a/a.kt b/a.kt
            +one
            diff --git a/b.kt b/b.kt
            -two
            -three
            """.trimIndent()
        )

        assertEquals(2, stats.size)
        assertEquals(1, stats.getValue("a.kt").added)
        assertEquals(2, stats.getValue("b.kt").removed)
        assertEquals(0, stats.getValue("b.kt").added)
    }

    @Test
    fun `понимает CRLF`() {
        val stats = parse("diff --git a/a.kt b/a.kt\r\n+one\r\n-two\r\n")

        assertEquals(1, stats.getValue("a.kt").added)
        assertEquals(1, stats.getValue("a.kt").removed)
    }

    @Test
    fun `путь берётся из части после пробел-b-слэш`() {
        val stats = parse("diff --git a/dir/sub/file.kt b/dir/sub/file.kt\n+x")

        assertEquals(setOf("dir/sub/file.kt"), stats.keys)
    }

    @Test
    fun `файл без изменений в дифф не попадает и статистики не имеет`() {
        val stats = parse("diff --git a/a.kt b/a.kt\n+x")

        assertNull(stats["b.kt"])
    }

    @Test
    fun `пустой вывод даёт пустую карту`() {
        assertTrue(parse("").isEmpty())
    }

    @Test
    fun `не-ASCII путь декодируется`() {
        val stats = parse("diff --git a/Модуль/файл.kt b/Модуль/файл.kt\n+x")

        assertEquals(setOf("Модуль/файл.kt"), stats.keys)
    }
}
