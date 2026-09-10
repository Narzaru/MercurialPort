package com.narzaru.mercurial.export

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportReportTest {

    @Test
    fun `a project file keeps its path relative to the project`() {
        assertEquals("src/a.kt", ExportReport.destination("D:/repo/src/a.kt", "D:/repo"))
    }

    @Test
    fun `a file outside the project goes to a folder of its own`() {
        val destination = ExportReport.destination("D:/other/a.kt", "D:/repo")

        assertEquals(File(ExportReport.EXTERNAL_DIR, "a.kt").path, destination)
    }

    @Test
    fun `a clean export reports the count and the path`() {
        assertEquals("Files copied: 2\nPath: D:/out", ExportReport.message(2, emptyList(), "D:/out"))
    }

    @Test
    fun `a file that failed to copy is named`() {
        val text = ExportReport.message(1, listOf("D:/a.kt — Access is denied"), "D:/out")

        assertTrue(text.contains("Failed: 1"))
        assertTrue(text.contains("D:/a.kt — Access is denied"))
    }

    @Test
    fun `a long list of failures is cut short`() {
        val failures = (1..15).map { "D:/f$it.kt — Access is denied" }

        val text = ExportReport.message(0, failures, "D:/out")

        assertTrue(text.contains("Failed: 15"))
        assertTrue(text.contains("D:/f10.kt"))
        assertFalse(text.contains("D:/f11.kt"))
        assertTrue(text.endsWith("…"))
    }
}
