package com.narzaru.mercurial.changes

import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

class HgDiffProcessor(project: Project, chain: DiffRequestChain) :
    CacheDiffRequestChainProcessor(project, chain) {

    fun reload() {
        dropCaches()
        updateRequest(true)
    }

    fun rightCaretLine(): Int? {
        val unified = activeViewer as? UnifiedDiffViewer
        if (unified != null) {
            val line = unified.editor.caretModel.logicalPosition.line
            return unified.transferLineFromOneside(Side.RIGHT, line).takeIf { it >= 0 }
        }
        return rightEditor()?.caretModel?.logicalPosition?.line
    }

    fun scrollToRightLine(line: Int): Boolean {
        val unified = activeViewer as? UnifiedDiffViewer
        if (unified != null) {
            val target = unified.transferLineToOneside(Side.RIGHT, line).takeIf { it >= 0 } ?: return false
            DiffUtil.scrollEditor(unified.editor, target, false)
            return true
        }
        val editor = rightEditor() ?: return false
        DiffUtil.scrollEditor(editor, line, false)
        return true
    }

    private fun rightEditor(): Editor? = when (val viewer = activeViewer) {
        is TwosideTextDiffViewer -> viewer.getEditor(Side.RIGHT)
        is OnesideTextDiffViewer -> viewer.editor
        else -> null
    }
}
