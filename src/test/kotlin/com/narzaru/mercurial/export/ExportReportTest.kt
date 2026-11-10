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
    fun `files outside the project with the same name do not overwrite each other`() {
        val destinations = ExportReport.destinations(
            listOf("D:/one/a.kt", "D:/repo/src/b.kt", "D:/two/A.kt", "D:/three/a.kt"),
            "D:/repo"
        )

        assertEquals(
            listOf(
                File(ExportReport.EXTERNAL_DIR, "a.kt").path,
                "src/b.kt",
                File(ExportReport.EXTERNAL_DIR, "A (2).kt").path,
                File(ExportReport.EXTERNAL_DIR, "a (3).kt").path
            ),
            destinations
        )
    }

    @Test
    fun `a file outside the project does not take the place of a project file`() {
        val external = File(ExportReport.EXTERNAL_DIR, "a.kt").path
        val destinations = ExportReport.destinations(
            listOf("D:/other/a.kt", "D:/repo/${ExportReport.EXTERNAL_DIR}/a.kt", "D:/other/.gitignore"),
            "D:/repo"
        )

        assertEquals(
            listOf(
                File(ExportReport.EXTERNAL_DIR, "a (2).kt").path,
                "${ExportReport.EXTERNAL_DIR}/a.kt",
                File(ExportReport.EXTERNAL_DIR, ".gitignore").path
            ),
            destinations
        )
        assertEquals(external, ExportReport.destination("D:/other/a.kt", "D:/repo"))
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
