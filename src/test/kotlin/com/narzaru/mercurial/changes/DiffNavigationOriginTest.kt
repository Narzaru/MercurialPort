package com.narzaru.mercurial.changes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffNavigationOriginTest {

    @Test
    fun `a file reached from its own diff is recognised`() {
        assertTrue(DiffNavigationOrigin.cameFromItsDiff("C:\\repo\\A.kt", "c:/repo/a.kt"))
    }

    @Test
    fun `a file reached from another diff is not its own`() {
        assertFalse(DiffNavigationOrigin.cameFromItsDiff("C:/repo/a.kt", "C:/repo/b.kt"))
    }

    @Test
    fun `a file reached from no diff at all is not its own`() {
        assertFalse(DiffNavigationOrigin.cameFromItsDiff(null, "C:/repo/a.kt"))
    }
}
