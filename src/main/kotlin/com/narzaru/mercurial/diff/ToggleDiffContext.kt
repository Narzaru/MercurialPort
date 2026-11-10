package com.narzaru.mercurial.diff

import com.narzaru.mercurial.changes.CaretTransfer
import com.narzaru.mercurial.changes.DiffCaret
import com.narzaru.mercurial.changes.HgChangesService
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile

object ToggleDiffContext {

    fun targetOf(context: DataContext): ToggleDiffTarget {
        val project = CommonDataKeys.PROJECT.getData(context) ?: return ToggleDiffTarget.None
        if (isInDiff(context)) return ToggleDiffPlan.of(true, false, false, null)
        val file = editedFileOf(context) ?: return ToggleDiffTarget.None
        val changes = project.service<HgChangesService>()
        val loaded = changes.changesLoaded()
        val tabKey = if (loaded && changes.hasDiff(file)) changes.diffTabKeyOf(file) else null
        return ToggleDiffPlan.of(false, true, loaded, tabKey)
    }

    fun editedFileOf(context: DataContext): VirtualFile? {
        if (CommonDataKeys.EDITOR.getData(context) == null) return null
        val file = CommonDataKeys.VIRTUAL_FILE.getData(context) ?: return null
        if (file.isDirectory || !file.isInLocalFileSystem) return null
        return file
    }

    fun caretOf(context: DataContext): DiffCaret? {
        val editor = CommonDataKeys.EDITOR.getData(context) ?: return null
        val offset = editor.caretModel.offset
        val line = editor.document.getLineNumber(offset)
        return CaretTransfer.toDiff(line, offset - editor.document.getLineStartOffset(line))
    }

    private fun isInDiff(context: DataContext): Boolean =
        DiffDataKeys.DIFF_CONTEXT.getData(context) != null || DiffDataKeys.DIFF_VIEWER.getData(context) != null
}
