package com.narzaru.mercurial.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTempFileNameTest {

    @Test
    fun `the extension stays at the end of the name`() {
        assertEquals(
            "App_open_rev12_bob.kt",
            HistoryTempFileName.of("App.kt", "open", "12", "bob")
        )
    }

    @Test
    fun `a name without an extension keeps no dot`() {
        assertEquals(
            "Makefile_open_rev7_bob",
            HistoryTempFileName.of("Makefile", "open", "7", "bob")
        )
    }

    @Test
    fun `only the last dot starts the extension`() {
        assertEquals(
            "archive.tar_open_rev7_bob.gz",
            HistoryTempFileName.of("archive.tar.gz", "open", "7", "bob")
        )
    }

    @Test
    fun `only the file name is taken from a path with directories`() {
        assertEquals(
            "App_open_rev7_bob.kt",
            HistoryTempFileName.of("src/main/kotlin/App.kt", "open", "7", "bob")
        )
    }

    @Test
    fun `characters unsafe for a file name are replaced`() {
        val name = HistoryTempFileName.of("App.kt", "open", "7", "Иван Петров")

        assertEquals("App_open_rev7" + "_".repeat(12) + ".kt", name)
        assertTrue(name.all { it.code < 128 })
    }

    @Test
    fun `different revisions give different names`() {
        val first = HistoryTempFileName.of("App.kt", "open", "7", "bob")
        val second = HistoryTempFileName.of("App.kt", "open", "8", "bob")

        assertNotEquals(first, second)
    }
}
