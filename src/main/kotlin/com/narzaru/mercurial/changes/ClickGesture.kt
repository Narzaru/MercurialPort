package com.narzaru.mercurial.changes

import java.awt.event.InputEvent

enum class FileClickAction { IGNORE, ACTIVATE, ACTIVATE_AFTER_GUARD, OPEN_FILE }

object ClickGesture {

    private const val SELECTION_MODIFIERS =
        InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK

    fun changesSelectionOnly(clickCount: Int, modifiersEx: Int): Boolean =
        clickCount == 1 && (modifiersEx and SELECTION_MODIFIERS) != 0

    fun onFile(clickCount: Int, modifiersEx: Int, alreadyActivated: Boolean): FileClickAction = when {
        changesSelectionOnly(clickCount, modifiersEx) -> FileClickAction.IGNORE
        clickCount == 1 && alreadyActivated -> FileClickAction.ACTIVATE_AFTER_GUARD
        clickCount == 1 -> FileClickAction.ACTIVATE
        clickCount == 2 -> FileClickAction.OPEN_FILE
        else -> FileClickAction.IGNORE
    }
}
