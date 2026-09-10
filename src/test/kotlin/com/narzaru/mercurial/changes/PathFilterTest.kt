package com.narzaru.mercurial.changes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathFilterTest {

    @Test
    fun `the filter matches a substring ignoring case`() {
        val filter = PathFilter(include = "MAIN", exclude = "")

        assertTrue(filter.accepts("src/main/a.kt"))
        assertFalse(filter.accepts("src/test/a.kt"))
    }

    @Test
    fun `the words of the filter work as OR`() {
        val filter = PathFilter(include = "main test", exclude = "")

        assertTrue(filter.accepts("src/main/a.kt"))
        assertTrue(filter.accepts("src/test/a.kt"))
        assertFalse(filter.accepts("src/other/a.kt"))
    }

    @Test
    fun `a pattern with a space is matched as a whole first`() {
        val filter = PathFilter(include = "dir with space", exclude = "")

        assertTrue(filter.accepts("dir with space/a.kt"))
    }

    @Test
    fun `an exclusion beats an inclusion`() {
        val filter = PathFilter(include = "src", exclude = "generated")

        assertTrue(filter.accepts("src/a.kt"))
        assertFalse(filter.accepts("src/generated/a.kt"))
    }

    @Test
    fun `a lone exclusion lets everything else through`() {
        val filter = PathFilter(include = "", exclude = "test")

        assertTrue(filter.accepts("src/a.kt"))
        assertFalse(filter.accepts("src/test/a.kt"))
    }

    @Test
    fun `spaces around a pattern do not matter`() {
        assertTrue(PathFilter(include = "  main  ", exclude = "").accepts("src/main/a.kt"))
        assertTrue(PathFilter(include = "", exclude = "   ").accepts("src/a.kt"))
    }
}
