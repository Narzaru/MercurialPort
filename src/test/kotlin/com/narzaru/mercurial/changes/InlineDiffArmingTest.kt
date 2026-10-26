package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineDiffArmingTest {

    private var now = 1_000L

    private val arming = InlineDiffArming { now }

    @Test
    fun `an armed jump remembers the diff it started in`() {
        arming.arm("C:/repo/a.kt", emptyList())
        assertEquals("C:/repo/a.kt", arming.peek()?.diffSourcePath)
    }

    @Test
    fun `peeking does not spend the arming`() {
        arming.arm("C:/repo/a.kt", emptyList())
        arming.peek()
        assertEquals("C:/repo/a.kt", arming.take()?.diffSourcePath)
    }

    @Test
    fun `an arming is taken only once`() {
        arming.arm("C:/repo/a.kt", emptyList())
        arming.take()
        assertNull(arming.take())
    }

    @Test
    fun `a stale arming is not given back`() {
        arming.arm("C:/repo/a.kt", emptyList())
        now += 60_000
        assertNull(arming.peek())
    }

    @Test
    fun `a later jump replaces the one before it`() {
        arming.arm("C:/repo/a.kt", emptyList())
        arming.arm("C:/repo/b.kt", emptyList())
        assertEquals("C:/repo/b.kt", arming.peek()?.diffSourcePath)
    }

    @Test
    fun `a disarmed jump is no longer armed`() {
        arming.arm("C:/repo/a.kt", emptyList())
        arming.disarm()
        assertNull(arming.peek())
    }

    @Test
    fun `nothing is armed until a jump arms it`() {
        assertNull(arming.peek())
    }

    @Test
    fun `a jump remembers which files were open when it started`() {
        arming.arm("C:/repo/a.kt", listOf("C:\\repo\\B.kt"))
        val jump = arming.peek()!!
        assertTrue(jump.wasOpenBefore("c:/repo/b.kt"))
        assertFalse(jump.wasOpenBefore("C:/repo/c.kt"))
    }

    @Test
    fun `a file opened after the jump started is not counted as open before it`() {
        arming.arm("C:/repo/a.kt", emptyList())
        assertFalse(arming.peek()!!.wasOpenBefore("C:/repo/c.kt"))
    }
}
