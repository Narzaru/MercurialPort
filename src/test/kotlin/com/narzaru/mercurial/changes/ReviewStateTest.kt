package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewStateTest {

    private class FakeStore(var saved: List<String> = emptyList()) : ReviewedPathsStore {
        override fun load(): List<String> = saved
        override fun save(paths: List<String>) {
            saved = paths
        }
    }

    private val store = FakeStore()
    private val state = ReviewState(store)

    private fun item(path: String) = HgFileItem(status = "M", path = path)

    @Test
    fun `a mark is set and dropped`() {
        val file = item("src/a.kt")

        assertFalse(state.isReviewed(file))
        state.set(listOf(file), true)
        assertTrue(state.isReviewed(file))
        state.set(listOf(file), false)
        assertFalse(state.isReviewed(file))
    }

    @Test
    fun `a mark ignores case and separators`() {
        state.set(listOf(item("src/a.kt")), true)

        assertTrue(state.isReviewed(item("SRC\\A.KT")))
    }

    @Test
    fun `marking again does not count as a change`() {
        val file = item("src/a.kt")

        assertTrue(state.set(listOf(file), true))
        assertFalse(state.set(listOf(file), true))
    }

    @Test
    fun `toggling a group marks everything first`() {
        val files = listOf(item("a.kt"), item("b.kt"))
        state.set(listOf(files[0]), true)

        state.toggle(files)

        assertTrue(files.all { state.isReviewed(it) })
    }

    @Test
    fun `toggling a fully marked group drops the marks`() {
        val files = listOf(item("a.kt"), item("b.kt"))
        state.set(files, true)

        state.toggle(files)

        assertTrue(files.none { state.isReviewed(it) })
    }

    @Test
    fun `toggling an empty group changes nothing`() {
        assertFalse(state.toggle(emptyList()))
    }

    @Test
    fun `clearing drops every mark and reports a change`() {
        state.set(listOf(item("a.kt")), true)

        assertTrue(state.clear())
        assertFalse(state.clear())
    }

    @Test
    fun `marks survive a reread from the store`() {
        state.set(listOf(item("src/A.kt")), true)

        val restored = ReviewState(store)
        restored.reload()

        assertTrue(restored.isReviewed(item("src/a.kt")))
    }

    @Test
    fun `a normalized key goes into the store`() {
        state.set(listOf(item("SRC\\A.kt")), true)

        assertEquals(listOf("src/a.kt"), store.saved)
    }
}
