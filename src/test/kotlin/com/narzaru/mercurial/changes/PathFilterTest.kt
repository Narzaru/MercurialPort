package com.narzaru.mercurial.changes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathFilterTest {

    @Test
    fun `пустой фильтр пропускает всё`() {
        assertTrue(PathFilter.ALL.accepts("src/a.kt"))
    }

    @Test
    fun `фильтр ищет подстроку без учёта регистра`() {
        val filter = PathFilter(include = "MAIN", exclude = "")

        assertTrue(filter.accepts("src/main/a.kt"))
        assertFalse(filter.accepts("src/test/a.kt"))
    }

    @Test
    fun `слова фильтра работают как ИЛИ`() {
        val filter = PathFilter(include = "main test", exclude = "")

        assertTrue(filter.accepts("src/main/a.kt"))
        assertTrue(filter.accepts("src/test/a.kt"))
        assertFalse(filter.accepts("src/other/a.kt"))
    }

    @Test
    fun `шаблон с пробелом сначала проверяется целиком`() {
        val filter = PathFilter(include = "dir with space", exclude = "")

        assertTrue(filter.accepts("dir with space/a.kt"))
    }

    @Test
    fun `исключение сильнее включения`() {
        val filter = PathFilter(include = "src", exclude = "generated")

        assertTrue(filter.accepts("src/a.kt"))
        assertFalse(filter.accepts("src/generated/a.kt"))
    }

    @Test
    fun `одно исключение без включения пропускает остальное`() {
        val filter = PathFilter(include = "", exclude = "test")

        assertTrue(filter.accepts("src/a.kt"))
        assertFalse(filter.accepts("src/test/a.kt"))
    }

    @Test
    fun `пробелы вокруг шаблона не значимы`() {
        assertTrue(PathFilter(include = "  main  ", exclude = "").accepts("src/main/a.kt"))
        assertTrue(PathFilter(include = "", exclude = "   ").accepts("src/a.kt"))
    }
}
