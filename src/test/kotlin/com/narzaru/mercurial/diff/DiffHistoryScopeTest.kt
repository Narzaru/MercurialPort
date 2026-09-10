package com.narzaru.mercurial.diff

import com.intellij.diff.impl.DiffSettingsHolder.IncludeInNavigationHistory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffHistoryScopeTest {

    @Test
    fun `a closed diff tab still keeps its place`() {
        assertTrue(DiffHistoryScope.keepsPlaces(IncludeInNavigationHistory.OnlyIfOpen))
    }

    @Test
    fun `always keeps places`() {
        assertTrue(DiffHistoryScope.keepsPlaces(IncludeInNavigationHistory.Always))
    }

    @Test
    fun `never drops places`() {
        assertFalse(DiffHistoryScope.keepsPlaces(IncludeInNavigationHistory.Never))
    }
}
