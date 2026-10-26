package com.narzaru.mercurial.changes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationArmingTest {

    @Test
    fun `a jump to a declaration made in a diff arms the redirect`() {
        assertTrue(NavigationArming.armsRedirect("GotoDeclaration", "C:/repo/a.kt", true))
    }

    @Test
    fun `a jump to an implementation made in a diff arms the redirect`() {
        assertTrue(NavigationArming.armsRedirect("GotoImplementation", "C:/repo/a.kt", true))
    }

    @Test
    fun `a jump made outside a diff arms nothing`() {
        assertFalse(NavigationArming.armsRedirect("GotoDeclaration", null, true))
    }

    @Test
    fun `an action that is not a jump arms nothing`() {
        assertFalse(NavigationArming.armsRedirect("EditorCopy", "C:/repo/a.kt", true))
    }

    @Test
    fun `an action without an id arms nothing`() {
        assertFalse(NavigationArming.armsRedirect(null, "C:/repo/a.kt", true))
    }

    @Test
    fun `a jump arms nothing while the setting is off`() {
        assertFalse(NavigationArming.armsRedirect("GotoDeclaration", "C:/repo/a.kt", false))
    }

    @Test
    fun `every arming action arms a jump made in a diff`() {
        assertTrue(NavigationArming.armingActionIds.all {
            NavigationArming.armsRedirect(it, "C:/repo/a.kt", true)
        })
    }
}
