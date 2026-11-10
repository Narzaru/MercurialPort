package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Test

class BranchSuggestionsTest {

    private val names = listOf("default", "feature-login", "Fix-ui", "hotfix", "prefix-tools", "stable")

    @Test
    fun `an empty text suggests every branch`() {
        assertEquals(names, BranchSuggestions.matching(names, ""))
        assertEquals(names, BranchSuggestions.matching(names, "  "))
    }

    @Test
    fun `branches containing the text are suggested ignoring case`() {
        assertEquals(listOf("stable"), BranchSuggestions.matching(names, "TaB"))
        assertEquals(listOf("feature-login"), BranchSuggestions.matching(names, "FEAT"))
    }

    @Test
    fun `prefix matches come before inner matches`() {
        assertEquals(listOf("Fix-ui", "hotfix", "prefix-tools"), BranchSuggestions.matching(names, "fix"))
    }

    @Test
    fun `each group keeps the order of the list`() {
        val ordered = listOf("default", "b-fe", "a-fe", "fe-z", "fe-a")

        assertEquals(listOf("fe-z", "fe-a", "b-fe", "a-fe"), BranchSuggestions.matching(ordered, "fe"))
    }

    @Test
    fun `surrounding spaces of the text are ignored`() {
        assertEquals(listOf("stable"), BranchSuggestions.matching(names, " stab "))
    }

    @Test
    fun `inner spaces must match`() {
        val spaced = listOf("release 2.0", "release-2.0")

        assertEquals(listOf("release 2.0"), BranchSuggestions.matching(spaced, "e 2"))
    }

    @Test
    fun `text matching nothing suggests nothing`() {
        assertEquals(emptyList<String>(), BranchSuggestions.matching(names, "zzz"))
    }

    @Test
    fun `cyrillic text matches ignoring case`() {
        assertEquals(listOf("Фича"), BranchSuggestions.matching(listOf("default", "Фича"), "фИ"))
    }

    @Test
    fun `down from no highlight goes to the first suggestion`() {
        assertEquals(0, BranchSuggestions.moved(BranchSuggestions.NONE, 3, down = true))
    }

    @Test
    fun `down stops at the last suggestion`() {
        assertEquals(2, BranchSuggestions.moved(1, 3, down = true))
        assertEquals(2, BranchSuggestions.moved(2, 3, down = true))
    }

    @Test
    fun `up from the first suggestion returns to the typed text`() {
        assertEquals(BranchSuggestions.NONE, BranchSuggestions.moved(0, 3, down = false))
        assertEquals(BranchSuggestions.NONE, BranchSuggestions.moved(BranchSuggestions.NONE, 3, down = false))
    }

    @Test
    fun `up moves to the previous suggestion`() {
        assertEquals(1, BranchSuggestions.moved(2, 3, down = false))
    }

    @Test
    fun `no suggestions means no highlight`() {
        assertEquals(BranchSuggestions.NONE, BranchSuggestions.moved(0, 0, down = true))
    }

    @Test
    fun `a highlight past a shrunk list is pulled back`() {
        assertEquals(1, BranchSuggestions.moved(5, 2, down = true))
        assertEquals(0, BranchSuggestions.moved(5, 2, down = false))
    }
}
