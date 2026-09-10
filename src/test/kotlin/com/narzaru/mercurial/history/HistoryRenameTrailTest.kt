package com.narzaru.mercurial.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryRenameTrailTest {

    private fun trail(
        revisions: List<String> = listOf("9", "8", "7", "6", "5", "4", "3", "2", "1", "0"),
        renames: Map<Pair<String, String>, String> = emptyMap(),
        maxLookups: Int = HistoryRenameTrail.DEFAULT_MAX_LOOKUPS
    ) = HistoryRenameTrail(
        revisionsBelow = { fromRow -> revisions.take(fromRow + 1).reversed() },
        renameSourceOf = { revision, path -> renames[revision to path] },
        maxLookups = maxLookups
    )

    @Test
    fun `a file that was never renamed is read at its own path`() {
        val found = trail().follow("src/App.kt", fromRow = 3) { path ->
            if (path == "src/App.kt") "text" else null
        }

        assertEquals("text", found.text)
        assertEquals("src/App.kt", found.path)
    }

    @Test
    fun `a single rename is followed to the previous path`() {
        val renames = mapOf(("6" to "src/New.kt") to "src/Old.kt")

        val found = trail(renames = renames).follow("src/New.kt", fromRow = 3) { path ->
            if (path == "src/Old.kt") "old text" else null
        }

        assertEquals("old text", found.text)
        assertEquals("src/Old.kt", found.path)
    }

    @Test
    fun `a chain of two renames is followed to the end`() {
        val renames = mapOf(
            ("6" to "src/New.kt") to "src/Middle.kt",
            ("7" to "src/Middle.kt") to "src/Old.kt"
        )

        val found = trail(renames = renames).follow("src/New.kt", fromRow = 3) { path ->
            if (path == "src/Old.kt") "old text" else null
        }

        assertEquals("old text", found.text)
        assertEquals("src/Old.kt", found.path)
    }

    @Test
    fun `the search gives up after the lookup limit`() {
        val renames = mapOf(
            ("6" to "src/New.kt") to "src/Middle.kt",
            ("8" to "src/Middle.kt") to "src/Old.kt"
        )

        val found = trail(renames = renames, maxLookups = 2).follow("src/New.kt", fromRow = 3) { path ->
            if (path == "src/Old.kt") "old text" else null
        }

        assertNull(found.text)
        assertEquals("src/Middle.kt", found.path)
    }

    @Test
    fun `a rename pointing at the current path is ignored`() {
        val renames = mapOf(
            ("6" to "src/New.kt") to "SRC/new.kt",
            ("7" to "src/New.kt") to "src/Old.kt"
        )

        val found = trail(renames = renames).follow("src/New.kt", fromRow = 3) { path ->
            if (path == "src/Old.kt") "old text" else null
        }

        assertEquals("old text", found.text)
        assertEquals("src/Old.kt", found.path)
    }

    @Test
    fun `nothing is found when neither the path nor its sources exist`() {
        val found = trail().follow("src/App.kt", fromRow = 0) { null }

        assertNull(found.text)
        assertEquals("src/App.kt", found.path)
    }
}
