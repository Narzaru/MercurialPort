package com.narzaru.mercurial.diff

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext

class ShowHgDiffPromoter : ActionPromoter {

    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val showDiff = actions.firstOrNull { it is ShowHgDiffAction } ?: return actions
        if (ShowHgDiffContext.targetOf(context) !is ShowHgDiffTarget.ShowDiff) return actions
        return listOf(showDiff) + actions.filterNot { it === showDiff }
    }
}
