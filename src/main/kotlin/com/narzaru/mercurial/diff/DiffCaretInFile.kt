package com.narzaru.mercurial.diff

import com.narzaru.mercurial.changes.CaretTransfer
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.LineCol
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException

object DiffCaretInFile {

    fun offsetIn(file: VirtualFile, viewer: FrameDiffTool.DiffViewer?): Int? {
        val caret = caretOf(viewer) ?: return null
        val target = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val line = CaretTransfer.toFile(caret.position.line, caret.document === target) {
            translate(caret.document, target, it)
        } ?: return null
        return LineCol(line, caret.position.column).toOffset(target)
    }

    private class Caret(val document: Document, val position: LineCol)

    private fun caretOf(viewer: FrameDiffTool.DiffViewer?): Caret? = when (viewer) {
        is UnifiedDiffViewer -> unifiedCaretOf(viewer)
        is TwosideTextDiffViewer -> editorCaretOf(viewer.currentEditor)
        is OnesideTextDiffViewer -> editorCaretOf(viewer.editor)
        else -> null
    }

    private fun editorCaretOf(editor: Editor): Caret = Caret(editor.document, LineCol.fromCaret(editor))

    private fun unifiedCaretOf(viewer: UnifiedDiffViewer): Caret {
        val position = LineCol.fromCaret(viewer.editor)
        val sides = viewer.transferLineFromOneside(position.line)
        val side = sides.second
        return Caret(viewer.getDocument(side), LineCol(side.select(sides.first), position.column))
    }

    private fun translate(from: Document, to: Document, line: Int): Int? =
        try {
            Diff.translateLine(from.charsSequence, to.charsSequence, line, true)
        } catch (_: FilesTooBigForDiffException) {
            null
        }
}
