package com.narzaru.mercurial.diff

import com.narzaru.mercurial.changes.HgChangesService
import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

object ToggleDiffContext {

    val DIFF_FILE: Key<HgDiffVirtualFile> = Key.create("mercurial.diff.file")

    val INLINE_DIFF_EDITOR: Key<HgInlineDiffEditor> = Key.create("mercurial.inline.diff.editor")

    fun targetOf(context: DataContext): ToggleDiffTarget {
        val project = CommonDataKeys.PROJECT.getData(context) ?: return ToggleDiffTarget.None
        if (inlineEditorOf(context) != null) {
            return ToggleDiffPlan.of(false, null, false, false, null, inInlineDiff = true)
        }
        val diffFile = diffFileOf(context)
        if (diffFile != null) return ToggleDiffPlan.of(true, diffFile.sourcePath, false, false, null)
        val file = editedFileOf(context) ?: return ToggleDiffTarget.None
        val changes = project.service<HgChangesService>()
        val loaded = changes.changesLoaded()
        val tabKey = if (loaded && changes.hasDiff(file)) changes.diffTabKeyOf(file) else null
        return ToggleDiffPlan.of(false, null, true, loaded, tabKey)
    }

    fun inlineEditorOf(context: DataContext): HgInlineDiffEditor? =
        PlatformCoreDataKeys.FILE_EDITOR.getData(context) as? HgInlineDiffEditor
            ?: diffContextOf(context)?.getUserData(INLINE_DIFF_EDITOR)

    fun diffSourcePathOf(context: DataContext): String? {
        inlineEditorOf(context)?.let { return if (it.showingDiff()) it.sourcePath else null }
        return diffFileOf(context)?.sourcePath
    }

    fun editedFileOf(context: DataContext): VirtualFile? {
        if (CommonDataKeys.EDITOR.getData(context) == null) return null
        val file = CommonDataKeys.VIRTUAL_FILE.getData(context) ?: return null
        if (file.isDirectory || !file.isInLocalFileSystem) return null
        return file
    }

    fun diffFileOf(context: DataContext): HgDiffVirtualFile? {
        (CommonDataKeys.VIRTUAL_FILE.getData(context) as? HgDiffVirtualFile)?.let { return it }
        (PlatformCoreDataKeys.FILE_EDITOR.getData(context)?.file as? HgDiffVirtualFile)?.let { return it }
        return diffContextOf(context)?.getUserData(DIFF_FILE)
    }

    fun diffViewerOf(context: DataContext): FrameDiffTool.DiffViewer? =
        DiffDataKeys.DIFF_VIEWER.getData(context) ?: diffFileOf(context)?.activeViewer()

    fun caretLineOf(context: DataContext): Int? =
        CommonDataKeys.EDITOR.getData(context)?.caretModel?.logicalPosition?.line

    private fun diffContextOf(context: DataContext): DiffContext? = DiffDataKeys.DIFF_CONTEXT.getData(context)
}
