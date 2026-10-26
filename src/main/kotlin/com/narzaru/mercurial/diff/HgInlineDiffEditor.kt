package com.narzaru.mercurial.diff

import com.narzaru.mercurial.changes.HgChangesService
import com.narzaru.mercurial.changes.HgDiffProcessor
import com.narzaru.mercurial.changes.HgDiffRedirect
import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager

private fun startLayoutOf(showDiff: Boolean): TextEditorWithPreview.Layout =
    if (showDiff) TextEditorWithPreview.Layout.SHOW_PREVIEW else TextEditorWithPreview.Layout.SHOW_EDITOR

class HgInlineDiffEditor(
    private val project: Project,
    private val fileEditor: TextEditor,
    private val diffEditor: DiffEditorViewerFileEditor,
    private val processor: HgDiffProcessor,
    val sourcePath: String,
    showDiff: Boolean
) : TextEditorWithPreview(
    fileEditor,
    diffEditor,
    NAME,
    startLayoutOf(showDiff),
    false,
    startLayoutOf(showDiff)
) {

    private val changes: HgChangesService = project.service()

    private var lineWaitingForDiff: Int? = fileEditor.editor.caretModel.logicalPosition.line

    init {
        processor.putContextUserData(ToggleDiffContext.INLINE_DIFF_EDITOR, this)
        processor.addListener({ showLineWaitingForDiff() }, this)
        fileEditor.editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (!showingDiff()) return
                lineWaitingForDiff = event.newPosition.line
                showLineWaitingForDiff()
            }
        }, this)
        changes.registerInlineDiff(sourcePath, this)
        LOG.info("${HgDiffRedirect.TRACE} inline editor created file=$sourcePath showDiff=$showDiff")
    }

    fun showingDiff(): Boolean = getLayout() == Layout.SHOW_PREVIEW

    fun revealFile() {
        if (!showingDiff()) return
        setLayout(Layout.SHOW_EDITOR)
        focusShownSide()
        LOG.info("${HgDiffRedirect.TRACE} inline reveal file=$sourcePath")
    }

    fun reloadDiff() {
        if (showingDiff()) processor.reload()
    }

    fun swap() {
        if (showingDiff()) {
            val offset = DiffCaretInFile.offsetIn(fileEditor.file, processor.activeViewer)
            setLayout(Layout.SHOW_EDITOR)
            if (offset != null) moveFileCaretTo(offset)
        } else {
            lineWaitingForDiff = fileEditor.editor.caretModel.logicalPosition.line
            setLayout(Layout.SHOW_PREVIEW)
            diffEditor.selectNotify()
            showLineWaitingForDiff()
        }
        focusShownSide()
        LOG.info("${HgDiffRedirect.TRACE} inline swap file=$sourcePath showingDiff=${showingDiff()}")
    }

    override fun selectNotify() {
        fileEditor.selectNotify()
        if (showingDiff()) diffEditor.selectNotify()
    }

    override val isShowFloatingToolbar: Boolean
        get() = true

    override fun createViewActionGroup(): ActionGroup = DefaultActionGroup()

    override fun dispose() {
        changes.unregisterInlineDiff(sourcePath, this)
        super.dispose()
    }

    private fun showLineWaitingForDiff() {
        val line = lineWaitingForDiff ?: return
        if (processor.scrollToRightLine(line)) lineWaitingForDiff = null
    }

    private fun moveFileCaretTo(offset: Int) {
        val editor = fileEditor.editor
        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private fun focusShownSide() {
        val target = preferredFocusedComponent ?: component
        IdeFocusManager.getInstance(project).requestFocus(target, true)
    }

    private companion object {
        const val NAME = "Hg Diff"

        val LOG = Logger.getInstance(HgInlineDiffEditor::class.java)
    }
}
