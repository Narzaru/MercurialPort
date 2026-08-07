package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewStateTest {

    /** Хранилище в памяти вместо PropertiesComponent проекта. */
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
    fun `отметка ставится и снимается`() {
        val file = item("src/a.kt")

        assertFalse(state.isReviewed(file))
        state.set(listOf(file), true)
        assertTrue(state.isReviewed(file))
        state.set(listOf(file), false)
        assertFalse(state.isReviewed(file))
    }

    @Test
    fun `отметка не зависит от регистра и разделителей`() {
        state.set(listOf(item("src/a.kt")), true)

        assertTrue(state.isReviewed(item("SRC\\A.KT")))
    }

    @Test
    fun `повторная отметка изменением не считается`() {
        val file = item("src/a.kt")

        assertTrue(state.set(listOf(file), true))
        assertFalse(state.set(listOf(file), true))
    }

    @Test
    fun `переключение группы сначала отмечает всё`() {
        val files = listOf(item("a.kt"), item("b.kt"))
        state.set(listOf(files[0]), true)

        // Отмечен только один — переключение доводит группу до «просмотрено всё».
        state.toggle(files)

        assertTrue(files.all { state.isReviewed(it) })
    }

    @Test
    fun `переключение полностью отмеченной группы снимает отметки`() {
        val files = listOf(item("a.kt"), item("b.kt"))
        state.set(files, true)

        state.toggle(files)

        assertTrue(files.none { state.isReviewed(it) })
    }

    @Test
    fun `переключение пустой группы ничего не меняет`() {
        assertFalse(state.toggle(emptyList()))
    }

    @Test
    fun `очистка снимает все отметки и сообщает об изменении`() {
        state.set(listOf(item("a.kt")), true)

        assertTrue(state.clear())
        assertTrue(state.isEmpty())
        assertFalse(state.clear())
    }

    @Test
    fun `отметки переживают перечитывание из хранилища`() {
        state.set(listOf(item("src/A.kt")), true)

        val restored = ReviewState(store)
        restored.reload()

        assertTrue(restored.isReviewed(item("src/a.kt")))
    }

    @Test
    fun `в хранилище уходит нормализованный ключ`() {
        state.set(listOf(item("SRC\\A.kt")), true)

        assertEquals(listOf("src/a.kt"), store.saved)
    }
}
