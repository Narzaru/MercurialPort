package com.narzaru.mercurial.changes

import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.diff.impl.DiffRequestProcessorListener
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Pair
import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException

class HgDiffProcessor(project: Project, chain: DiffRequestChain) :
    CacheDiffRequestChainProcessor(project, chain) {

    init {
        addListener(DiffRequestProcessorListener { watchRequestedCaret() }, this)
    }

    fun reload() {
        dropCaches()
        updateRequest(true)
    }

    fun placeRightCaret(caret: DiffCaret): Boolean {
        val request = (activeViewer as? DiffViewerBase)?.request ?: return false
        return placeCaretOnRightSide(rightSideCaret(request, caret))
    }

    private fun placeCaretOnRightSide(caret: DiffCaret): Boolean {
        val target = rightTarget(caret.line) ?: return false
        moveCaret(target.editor, target.line, caret.column)
        return true
    }

    private class RightTarget(val editor: Editor, val line: Int)

    private fun rightTarget(line: Int): RightTarget? {
        val unified = activeViewer as? UnifiedDiffViewer
        if (unified != null) {
            val target = unified.transferLineToOneside(Side.RIGHT, line).takeIf { it >= 0 } ?: return null
            return RightTarget(unified.editor, target)
        }
        return rightEditor()?.let { RightTarget(it, line) }
    }

    private fun rightEditor(): Editor? = when (val viewer = activeViewer) {
        is TwosideTextDiffViewer -> viewer.getEditor(Side.RIGHT)
        is OnesideTextDiffViewer -> viewer.editor
        else -> null
    }

    private fun moveCaret(editor: Editor, line: Int, column: Int) {
        val document = editor.document
        if (document.lineCount == 0) return
        val target = line.coerceIn(0, document.lineCount - 1)
        val start = document.getLineStartOffset(target)
        val length = document.getLineEndOffset(target) - start
        editor.caretModel.moveToOffset(start + CaretTransfer.columnInLine(column, length))
        DiffUtil.scrollToCaret(editor, false)
    }

    private fun watchRequestedCaret() {
        val viewer = activeViewer as? DiffViewerBase ?: return
        if (viewer.request.getUserData(REQUESTED_CARET) == null) return
        viewer.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() {
                viewer.removeListener(this)
                placeRequestedCaret(viewer)
            }
        })
        ApplicationManager.getApplication().invokeLater(
            { placeRequestedCaretIfScrolled(viewer) },
            Condition<Any> { viewer.isDisposed || isDisposed }
        )
    }

    private fun placeRequestedCaretIfScrolled(viewer: DiffViewerBase) {
        val caret = viewer.request.getUserData(REQUESTED_CARET) ?: return
        val target = rightTarget(caret.line) ?: return
        if (target.editor.caretModel.logicalPosition.line != target.line) return
        placeRequestedCaret(viewer)
    }

    private fun placeRequestedCaret(viewer: DiffViewerBase) {
        if (viewer.isDisposed || viewer !== activeViewer) return
        val caret = viewer.request.getUserData(REQUESTED_CARET) ?: return
        if (placeCaretOnRightSide(caret)) viewer.request.putUserData(REQUESTED_CARET, null)
    }

    companion object {
        private val REQUESTED_CARET: Key<DiffCaret> = Key.create("mercurial.diff.requested.caret")

        fun requestRightCaret(request: ContentDiffRequest, caret: DiffCaret) {
            val onRightSide = rightSideCaret(request, caret)
            request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(Side.RIGHT, onRightSide.line))
            request.putUserData(REQUESTED_CARET, onRightSide)
        }

        private fun rightSideCaret(request: ContentDiffRequest, caret: DiffCaret): DiffCaret {
            val right = request.contents.getOrNull(1) as? DocumentContent ?: return caret
            val file = right.highlightFile ?: return caret
            val line = ReadAction.compute<Int, RuntimeException> {
                val fileDocument = FileDocumentManager.getInstance().getDocument(file)
                if (fileDocument == null) caret.line
                else CaretTransfer.lineOnRightSide(caret.line, fileDocument === right.document) {
                    translate(fileDocument, right.document, it)
                }
            }
            return caret.copy(line = line)
        }

        private fun translate(from: Document, to: Document, line: Int): Int? =
            try {
                Diff.translateLine(from.charsSequence, to.charsSequence, line, true)
            } catch (_: FilesTooBigForDiffException) {
                null
            }
    }
}
