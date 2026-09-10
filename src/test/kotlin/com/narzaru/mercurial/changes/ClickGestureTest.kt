package com.narzaru.mercurial.changes

import java.awt.event.InputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickGestureTest {

    @Test
    fun `a plain click activates the row`() {
        assertFalse(ClickGesture.changesSelectionOnly(1, 0))
    }

    @Test
    fun `a shift click only extends the selection`() {
        assertTrue(ClickGesture.changesSelectionOnly(1, InputEvent.SHIFT_DOWN_MASK))
    }

    @Test
    fun `a ctrl click only changes the selection`() {
        assertTrue(ClickGesture.changesSelectionOnly(1, InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun `a meta click only changes the selection`() {
        assertTrue(ClickGesture.changesSelectionOnly(1, InputEvent.META_DOWN_MASK))
    }

    @Test
    fun `an alt click still activates the row`() {
        assertFalse(ClickGesture.changesSelectionOnly(1, InputEvent.ALT_DOWN_MASK))
    }

    @Test
    fun `a double click with a modifier is not a selection change`() {
        assertFalse(ClickGesture.changesSelectionOnly(2, InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun `a click on a new file activates it right away`() {
        assertEquals(FileClickAction.ACTIVATE, ClickGesture.onFile(1, 0, false))
    }

    @Test
    fun `a click on the activated file waits for a possible double click`() {
        assertEquals(FileClickAction.ACTIVATE_AFTER_GUARD, ClickGesture.onFile(1, 0, true))
    }

    @Test
    fun `a click with a pressed mouse button still activates the file`() {
        assertEquals(
            FileClickAction.ACTIVATE,
            ClickGesture.onFile(1, InputEvent.BUTTON1_DOWN_MASK, false)
        )
    }

    @Test
    fun `a ctrl click keeps the diff closed`() {
        assertEquals(FileClickAction.IGNORE, ClickGesture.onFile(1, InputEvent.CTRL_DOWN_MASK, false))
    }

    @Test
    fun `a double click opens the file`() {
        assertEquals(FileClickAction.OPEN_FILE, ClickGesture.onFile(2, 0, true))
    }

    @Test
    fun `a triple click does nothing`() {
        assertEquals(FileClickAction.IGNORE, ClickGesture.onFile(3, 0, true))
    }
}
