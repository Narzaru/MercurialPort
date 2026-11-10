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
        maxPathsPerBranch: Int = ReviewMarks.MAX_PATHS_PER_BRANCH
    ) = ReviewMarks(store, maxBranches, maxHashesPerPath, maxPathsPerBranch).also { it.reload() }

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
    fun `the least recently marked path of a branch goes first over the limit`() {
        val marks = marks(maxPathsPerBranch = 2)

        marks.add("feature", listOf(mark("a.kt", "h1")))
        marks.add("feature", listOf(mark("b.kt", "h1")))
        marks.add("feature", listOf(mark("c.kt", "h1")))

        assertFalse(marks.contains("feature", "a.kt", "h1"))
        assertTrue(marks.contains("feature", "b.kt", "h1"))
        assertTrue(marks.contains("feature", "c.kt", "h1"))
    }

    @Test
    fun `marking a path again saves it from eviction`() {
        val marks = marks(maxPathsPerBranch = 2)
        marks.add("feature", listOf(mark("a.kt", "h1")))
        marks.add("feature", listOf(mark("b.kt", "h1")))

        marks.add("feature", listOf(mark("a.kt", "h1")))
        marks.add("feature", listOf(mark("c.kt", "h1")))

        assertTrue(marks.contains("feature", "a.kt", "h1"))
        assertFalse(marks.contains("feature", "b.kt", "h1"))
        assertEquals(listOf("h1 a.kt", "h1 c.kt"), store.saved["feature"])
    }

    @Test
    fun `the limit counts paths, not hashes`() {
        val marks = marks(maxPathsPerBranch = 2)

        marks.add("feature", listOf(mark("a.kt", "h1"), mark("a.kt", "h2"), mark("a.kt", "h3")))
        marks.add("feature", listOf(mark("b.kt", "h1"), mark("b.kt", "h2")))

        assertEquals(listOf("h1,h2,h3 a.kt", "h1,h2 b.kt"), store.saved["feature"])
    }

    @Test
    fun `stored paths beyond the limit are dropped on load and on the next save`() {
        store.order = listOf("feature")
        store.saved["feature"] = listOf("h1 a.kt", "h1 b.kt", "h1 c.kt", "h1 d.kt")
        val marks = marks(maxPathsPerBranch = 2)

        assertFalse(marks.contains("feature", "b.kt", "h1"))
        assertTrue(marks.contains("feature", "c.kt", "h1"))

        marks.add("feature", listOf(mark("e.kt", "h1")))

        assertEquals(listOf("h1 d.kt", "h1 e.kt"), store.saved["feature"])
    }

    @Test
    fun `a path stored twice keeps its later position`() {
        store.order = listOf("feature")
        store.saved["feature"] = listOf("h1 a.kt", "h1 b.kt", "h2 a.kt")
        val marks = marks(maxPathsPerBranch = 2)

        marks.add("feature", listOf(mark("c.kt", "h1")))

        assertEquals(listOf("h2 a.kt", "h1 c.kt"), store.saved["feature"])
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

        assertEquals(listOf("one", "two"), store.order.sorted())
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
