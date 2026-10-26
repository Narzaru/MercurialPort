package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewStateTest {

    private val store = FakeReviewMarksStore()
    private val state = ReviewState(ReviewMarks(store)).also { it.reload() }

    private fun item(path: String) = HgFileItem(status = "M", path = path)

    private fun loaded(
        branch: String = "feature",
        context: String = "UNCOMMITTED|.",
        hashes: Array<Pair<String, String>> = emptyArray()
    ) {
        state.startLoad(branch, context)
        state.learn(mapOf(*hashes))
    }

    @Test
    fun `a mark is set and dropped`() {
        loaded(hashes = arrayOf("src/a.kt" to "h1"))
        val file = item("src/a.kt")

        assertFalse(state.isReviewed(file))
        assertTrue(state.set(listOf(file), true))
        assertTrue(state.isReviewed(file))
        assertTrue(state.set(listOf(file), false))
        assertFalse(state.isReviewed(file))
    }

    @Test
    fun `an untracked file loses its mark when edited and gets it back when reverted`() {
        val file = HgFileItem(status = "?", path = "notes.txt")
        val content = mutableMapOf("notes.txt" to "c:first")
        fun recount() {
            state.suspend(listOf(state.key(file)))
            state.learn(DiffStatsPlan.diffHashes(listOf(file), emptyMap(), ".", DiffStatsPlan.contentHashes(listOf(file.path)) { content[it] }))
        }
        loaded()
        recount()
        state.set(listOf(file), true)

        content["notes.txt"] = "c:second"
        recount()
        assertFalse(state.isReviewed(file))

        content["notes.txt"] = "c:first"
        recount()
        assertTrue(state.isReviewed(file))

        content.remove("notes.txt")
        recount()
        assertTrue(state.isReviewed(file))
    }

    @Test
    fun `a mark ignores case and separators`() {
        loaded(hashes = arrayOf("src/a.kt" to "h1"))
        state.set(listOf(item("src/a.kt")), true)

        assertTrue(state.isReviewed(item("SRC\\A.KT")))
    }

    @Test
    fun `marking again does not count as a change`() {
        loaded(hashes = arrayOf("src/a.kt" to "h1"))
        val file = item("src/a.kt")

        assertTrue(state.set(listOf(file), true))
        assertFalse(state.set(listOf(file), true))
    }

    @Test
    fun `toggling a group marks everything first`() {
        loaded(hashes = arrayOf("a.kt" to "h1", "b.kt" to "h2"))
        val files = listOf(item("a.kt"), item("b.kt"))
        state.set(listOf(files[0]), true)

        state.toggle(files)

        assertTrue(files.all { state.isReviewed(it) })
    }

    @Test
    fun `toggling a fully marked group drops the marks`() {
        loaded(hashes = arrayOf("a.kt" to "h1", "b.kt" to "h2"))
        val files = listOf(item("a.kt"), item("b.kt"))
        state.set(files, true)

        state.toggle(files)

        assertTrue(files.none { state.isReviewed(it) })
    }

    @Test
    fun `toggling an empty group changes nothing`() {
        loaded()

        assertFalse(state.toggle(emptyList()))
    }

    @Test
    fun `a changed diff drops the mark and the old diff brings it back`() {
        loaded(hashes = arrayOf("a.kt" to "h1"))
        state.set(listOf(item("a.kt")), true)

        assertTrue(state.learn(mapOf("a.kt" to "h2")))
        assertFalse(state.isReviewed(item("a.kt")))

        state.learn(mapOf("a.kt" to "h1"))
        assertTrue(state.isReviewed(item("a.kt")))
    }

    @Test
    fun `learning the same hash reports no change`() {
        loaded(hashes = arrayOf("a.kt" to "h1"))

        assertFalse(state.learn(mapOf("a.kt" to "h1")))
    }

    @Test
    fun `marks of one branch are not seen on another`() {
        loaded("one", hashes = arrayOf("a.kt" to "h1"))
        state.set(listOf(item("a.kt")), true)

        loaded("two", hashes = arrayOf("a.kt" to "h1"))
        assertFalse(state.isReviewed(item("a.kt")))

        loaded("one", hashes = arrayOf("a.kt" to "h1"))
        assertTrue(state.isReviewed(item("a.kt")))
    }

    @Test
    fun `nothing is read or written while the branch is unknown`() {
        loaded("", hashes = arrayOf("a.kt" to "h1"))

        assertFalse(state.set(listOf(item("a.kt")), true))
        assertFalse(state.isReviewed(item("a.kt")))
        assertTrue(store.orders.isEmpty())
    }

    @Test
    fun `a file without a known diff shows unreviewed`() {
        loaded(hashes = arrayOf("a.kt" to "h1"))
        state.set(listOf(item("a.kt")), true)

        state.startLoad("feature", "BRANCH|base")

        assertFalse(state.isReviewed(item("a.kt")))
    }

    @Test
    fun `a mark set before the diff is known lands on the diff once it arrives`() {
        state.startLoad("feature", "UNCOMMITTED|.")

        assertTrue(state.set(listOf(item("a.kt")), true))
        assertTrue(state.isReviewed(item("a.kt")))
        assertTrue(store.saved.isEmpty())

        state.learn(mapOf("a.kt" to "h1"))

        assertTrue(state.isReviewed(item("a.kt")))
        assertEquals(listOf("h1 a.kt"), store.saved["feature"])
    }

    @Test
    fun `a reload of the same comparison keeps the marks shown while the diff is recounted`() {
        loaded(hashes = arrayOf("a.kt" to "h1"))
        state.set(listOf(item("a.kt")), true)

        state.startLoad("feature", "UNCOMMITTED|.")

        assertTrue(state.isReviewed(item("a.kt")))
    }

    @Test
    fun `a pending recount keeps the old state until the new diff arrives`() {
        loaded(hashes = arrayOf("a.kt" to "h1"))
        state.set(listOf(item("a.kt")), true)

        state.suspend(listOf("a.kt"))
        assertTrue(state.isReviewed(item("a.kt")))

        state.learn(mapOf("a.kt" to "h2"))
        assertFalse(state.isReviewed(item("a.kt")))
    }

    @Test
    fun `a mark set during a recount applies to the recounted diff`() {
        loaded(hashes = arrayOf("a.kt" to "h1"))
        state.suspend(listOf("a.kt"))

        state.set(listOf(item("a.kt")), true)
        state.learn(mapOf("a.kt" to "h2"))

        assertTrue(state.isReviewed(item("a.kt")))
        assertEquals(listOf("h2 a.kt"), store.saved["feature"])
    }

    @Test
    fun `an unmark set during a recount applies to the recounted diff`() {
        loaded(hashes = arrayOf("a.kt" to "h1"))
        state.set(listOf(item("a.kt")), true)
        state.suspend(listOf("a.kt"))

        state.set(listOf(item("a.kt")), false)
        assertFalse(state.isReviewed(item("a.kt")))
        state.learn(mapOf("a.kt" to "h1"))

        assertFalse(state.isReviewed(item("a.kt")))
    }

    @Test
    fun `a failed recount settles the mark on the last known diff`() {
        loaded(hashes = arrayOf("a.kt" to "h1"))
        state.suspend(listOf("a.kt"))
        state.set(listOf(item("a.kt")), true)

        state.learn(emptyMap())

        assertEquals(listOf("h1 a.kt"), store.saved["feature"])
        assertFalse(state.set(listOf(item("a.kt")), true))
    }

    @Test
    fun `unmarking keeps the marks of the other diffs of the file`() {
        loaded(hashes = arrayOf("a.kt" to "h1"))
        state.set(listOf(item("a.kt")), true)
        state.learn(mapOf("a.kt" to "h2"))
        state.set(listOf(item("a.kt")), true)

        state.set(listOf(item("a.kt")), false)
        state.learn(mapOf("a.kt" to "h1"))

        assertTrue(state.isReviewed(item("a.kt")))
    }

    @Test
    fun `a todo item shares the mark of its file`() {
        loaded(hashes = arrayOf("a.kt" to "h1"))
        state.set(listOf(item("a.kt")), true)

        assertTrue(state.isReviewed(item("a.kt").copy(lineNumber = 12, todoText = "TODO")))
    }

    @Test
    fun `clearing drops the marks of the current branch only`() {
        loaded("one", hashes = arrayOf("a.kt" to "h1"))
        state.set(listOf(item("a.kt")), true)
        loaded("two", hashes = arrayOf("a.kt" to "h1"))
        state.set(listOf(item("a.kt")), true)

        assertTrue(state.clear())
        assertFalse(state.isReviewed(item("a.kt")))
        assertFalse(state.clear())

        loaded("one", hashes = arrayOf("a.kt" to "h1"))
        assertTrue(state.isReviewed(item("a.kt")))
    }

    @Test
    fun `clearing drops marks still waiting for their diff`() {
        state.startLoad("feature", "UNCOMMITTED|.")
        state.set(listOf(item("a.kt")), true)

        assertTrue(state.clear())
        state.learn(mapOf("a.kt" to "h1"))

        assertFalse(state.isReviewed(item("a.kt")))
    }

    @Test
    fun `marks survive a reread from the store`() {
        loaded(hashes = arrayOf("src/a.kt" to "h1"))
        state.set(listOf(item("src/A.kt")), true)

        val restored = ReviewState(ReviewMarks(store)).also { it.reload() }
        restored.startLoad("feature", "UNCOMMITTED|.")
        restored.learn(mapOf("src/a.kt" to "h1"))

        assertTrue(restored.isReviewed(item("src/a.kt")))
    }
}
