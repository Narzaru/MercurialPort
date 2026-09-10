package com.narzaru.mercurial.diff

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext

class ToggleDiffPromoter : ActionPromoter {

    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val toggle = actions.firstOrNull { it is ToggleDiffAndFileAction } ?: return actions
        if (!ToggleDiffPromotion.winsOverJumpToSource(ToggleDiffContext.targetOf(context))) return actions
        return listOf(toggle) + actions.filterNot { it === toggle }
    }
}
