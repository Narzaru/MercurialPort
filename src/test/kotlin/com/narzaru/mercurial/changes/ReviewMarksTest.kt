package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewMarksTest {

    private val store = FakeReviewMarksStore()

    private fun marks(
        maxBranches: Int = ReviewMarks.MAX_BRANCHES,
        maxHashesPerPath: Int = ReviewMarks.MAX_HASHES_PER_PATH,
        maxMarksPerBranch: Int = ReviewMarks.MAX_MARKS_PER_BRANCH
    ) = ReviewMarks(store, maxBranches, maxHashesPerPath, maxMarksPerBranch).also { it.reload() }

    private fun mark(path: String, hash: String) = ReviewMark(path, hash)

    @Test
    fun `a mark is found by branch, path and hash`() {
        val marks = marks()

        assertTrue(marks.add("feature", listOf(mark("a.kt", "h1"))))

        assertTrue(marks.contains("feature", "a.kt", "h1"))
        assertFalse(marks.contains("feature", "a.kt", "h2"))
        assertFalse(marks.contains("feature", "b.kt", "h1"))
        assertFalse(marks.contains("other", "a.kt", "h1"))
    }

    @Test
    fun `adding a known mark is not a change`() {
        val marks = marks()
        marks.add("feature", listOf(mark("a.kt", "h1")))

        assertFalse(marks.add("feature", listOf(mark("a.kt", "h1"))))
    }

    @Test
    fun `marks are kept in the store and read back`() {
        marks().add("feature", listOf(mark("src/a b.kt", "h1"), mark("src/c.kt", "h2")))

        val restored = marks()

        assertTrue(restored.contains("feature", "src/a b.kt", "h1"))
        assertTrue(restored.contains("feature", "src/c.kt", "h2"))
        assertEquals(listOf("feature"), store.order)
    }

    @Test
    fun `only the last hashes of a path are kept`() {
        val marks = marks(maxHashesPerPath = 2)

        marks.add("feature", listOf(mark("a.kt", "h1")))
        marks.add("feature", listOf(mark("a.kt", "h2")))
        marks.add("feature", listOf(mark("a.kt", "h3")))

        assertFalse(marks.contains("feature", "a.kt", "h1"))
        assertTrue(marks.contains("feature", "a.kt", "h2"))
        assertTrue(marks.contains("feature", "a.kt", "h3"))
        assertEquals(listOf("h2,h3 a.kt"), store.saved["feature"])
    }

    @Test
    fun `marking a hash again makes it the newest`() {
        val marks = marks(maxHashesPerPath = 2)

        marks.add("feature", listOf(mark("a.kt", "h1")))
        marks.add("feature", listOf(mark("a.kt", "h2")))
        marks.add("feature", listOf(mark("a.kt", "h1")))
        marks.add("feature", listOf(mark("a.kt", "h3")))

        assertTrue(marks.contains("feature", "a.kt", "h1"))
        assertFalse(marks.contains("feature", "a.kt", "h2"))
    }

    @Test
    fun `the oldest marks of a branch go first over the limit`() {
        val marks = marks(maxMarksPerBranch = 2)

        marks.add("feature", listOf(mark("a.kt", "h1")))
        marks.add("feature", listOf(mark("b.kt", "h1")))
        marks.add("feature", listOf(mark("c.kt", "h1")))

        assertFalse(marks.contains("feature", "a.kt", "h1"))
        assertTrue(marks.contains("feature", "b.kt", "h1"))
        assertTrue(marks.contains("feature", "c.kt", "h1"))
    }

    @Test
    fun `removing drops only the given hash`() {
        val marks = marks()
        marks.add("feature", listOf(mark("a.kt", "h1"), mark("a.kt", "h2")))

        assertTrue(marks.remove("feature", listOf(mark("a.kt", "h2"))))

        assertTrue(marks.contains("feature", "a.kt", "h1"))
        assertFalse(marks.contains("feature", "a.kt", "h2"))
        assertFalse(marks.remove("feature", listOf(mark("a.kt", "h2"))))
    }

    @Test
    fun `a branch without marks gives up its place`() {
        val marks = marks()
        marks.add("feature", listOf(mark("a.kt", "h1")))

        marks.remove("feature", listOf(mark("a.kt", "h1")))

        assertEquals(emptyList<String>(), store.order)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `the least recently used branch is evicted past the limit`() {
        val marks = marks(maxBranches = 2)

        marks.add("one", listOf(mark("a.kt", "h1")))
        marks.add("two", listOf(mark("a.kt", "h1")))
        marks.add("three", listOf(mark("a.kt", "h1")))

        assertFalse(marks.contains("one", "a.kt", "h1"))
        assertTrue(marks.contains("two", "a.kt", "h1"))
        assertTrue(marks.contains("three", "a.kt", "h1"))
        assertEquals(listOf("three", "two"), store.order)
        assertEquals(setOf("two", "three"), store.saved.keys)
    }

    @Test
    fun `visiting a branch keeps its marks from eviction`() {
        val marks = marks(maxBranches = 2)
        marks.add("one", listOf(mark("a.kt", "h1")))
        marks.add("two", listOf(mark("a.kt", "h1")))

        marks.touch("one")
        marks.add("three", listOf(mark("a.kt", "h1")))

        assertTrue(marks.contains("one", "a.kt", "h1"))
        assertFalse(marks.contains("two", "a.kt", "h1"))
    }

    @Test
    fun `visiting a branch without marks takes no place`() {
        val marks = marks(maxBranches = 2)
        marks.add("one", listOf(mark("a.kt", "h1")))
        marks.add("two", listOf(mark("a.kt", "h1")))

        marks.touch("three")
        marks.touch("four")

        assertEquals(listOf("one", "two"), marks.branches().sorted())
    }

    @Test
    fun `clearing a branch leaves the others`() {
        val marks = marks()
        marks.add("one", listOf(mark("a.kt", "h1")))
        marks.add("two", listOf(mark("a.kt", "h1")))

        assertTrue(marks.clear("one"))

        assertFalse(marks.contains("one", "a.kt", "h1"))
        assertTrue(marks.contains("two", "a.kt", "h1"))
        assertEquals(listOf("two"), store.order)
        assertFalse(marks.clear("one"))
    }

    @Test
    fun `an unknown branch takes no marks`() {
        assertFalse(marks().add("", listOf(mark("a.kt", "h1"))))
        assertTrue(store.orders.isEmpty())
    }

    @Test
    fun `malformed stored entries are skipped`() {
        store.order = listOf("feature")
        store.saved["feature"] = listOf("", "nospace", " a.kt", "h1 ", ",, b.kt", "h1,h2 c.kt")

        val marks = marks()

        assertTrue(marks.contains("feature", "c.kt", "h1"))
        assertTrue(marks.contains("feature", "c.kt", "h2"))
        assertFalse(marks.contains("feature", "a.kt", "h1"))
        assertFalse(marks.contains("feature", "b.kt", ""))
    }

    @Test
    fun `stored branches beyond the limit are ignored`() {
        store.order = listOf("one", "two", "three")
        store.saved["three"] = listOf("h1 a.kt")

        assertFalse(marks(maxBranches = 2).contains("three", "a.kt", "h1"))
    }

    @Test
    fun `a storage name is safe for any branch name`() {
        assertEquals("64656661756c74", ReviewMarks.storageName("default"))
        assertEquals("612f62203a63", ReviewMarks.storageName("a/b :c"))
        assertEquals("d0b2", ReviewMarks.storageName("в"))
    }
}
