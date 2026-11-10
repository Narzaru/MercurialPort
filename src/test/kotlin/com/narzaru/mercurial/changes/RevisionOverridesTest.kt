package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionOverridesTest {

    private class FakeStore : RevisionOverridesStore {

        var order: List<String> = emptyList()
        val orders = ArrayList<List<String>>()
        val saved = LinkedHashMap<String, List<String>>()

        override fun branchOrder(): List<String> = order

        override fun saveBranchOrder(branches: List<String>) {
            order = branches
            orders.add(branches)
        }

        override fun overrides(branch: String): List<String> = saved[branch].orEmpty()

        override fun saveOverrides(branch: String, values: List<String>) {
            if (values.isEmpty()) saved.remove(branch) else saved[branch] = values
        }
    }

    private val store = FakeStore()

    private fun overrides(maxBranches: Int = RevisionOverrides.MAX_BRANCHES) = RevisionOverrides(store, maxBranches)

    @Test
    fun `overrides are kept per branch`() {
        val overrides = overrides()

        overrides.set("one", listOf("+a"))
        overrides.set("two", listOf("-b"))

        assertEquals(listOf("+a"), overrides.get("one"))
        assertEquals(listOf("-b"), overrides.get("two"))
        assertEquals(emptyList<String>(), overrides.get("three"))
    }

    @Test
    fun `the least recently used branch is evicted past the limit`() {
        val overrides = overrides(maxBranches = 2)

        overrides.set("one", listOf("+a"))
        overrides.set("two", listOf("+a"))
        overrides.set("three", listOf("+a"))

        assertEquals(setOf("two", "three"), store.saved.keys)
        assertEquals(listOf("three", "two"), store.order)
    }

    @Test
    fun `reading a branch keeps its overrides from eviction`() {
        val overrides = overrides(maxBranches = 2)
        overrides.set("one", listOf("+a"))
        overrides.set("two", listOf("+a"))

        overrides.get("one")
        overrides.set("three", listOf("+a"))

        assertEquals(setOf("one", "three"), store.saved.keys)
    }

    @Test
    fun `reading a branch without overrides takes no place`() {
        val overrides = overrides(maxBranches = 2)
        overrides.set("one", listOf("+a"))

        overrides.get("two")

        assertEquals(listOf("one"), store.order)
    }

    @Test
    fun `reading the most recent branch does not rewrite the order`() {
        val overrides = overrides()
        overrides.set("one", listOf("+a"))
        val writes = store.orders.size

        overrides.get("one")

        assertEquals(writes, store.orders.size)
    }

    @Test
    fun `clearing overrides removes the branch and its list`() {
        val overrides = overrides()
        overrides.set("one", listOf("+a"))
        overrides.set("two", listOf("+a"))

        overrides.set("one", emptyList())

        assertEquals(setOf("two"), store.saved.keys)
        assertEquals(listOf("two"), store.order)
    }

    @Test
    fun `clearing an unknown branch leaves the order alone`() {
        overrides().set("one", emptyList())

        assertTrue(store.orders.isEmpty())
    }

    @Test
    fun `a list stored before the order existed is adopted on read`() {
        store.saved["legacy"] = listOf("+a")
        val overrides = overrides(maxBranches = 2)

        assertEquals(listOf("+a"), overrides.get("legacy"))
        overrides.set("one", listOf("+a"))
        overrides.set("two", listOf("+a"))

        assertEquals(listOf("two", "one"), store.order)
        assertEquals(setOf("one", "two"), store.saved.keys)
    }
}
