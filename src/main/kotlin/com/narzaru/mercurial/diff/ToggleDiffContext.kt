package com.narzaru.mercurial.diff

import com.narzaru.mercurial.changes.HgChangesService
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile

object ToggleDiffContext {

    fun targetOf(context: DataContext): ToggleDiffTarget {
        val project = CommonDataKeys.PROJECT.getData(context) ?: return ToggleDiffTarget.None
        val diffFile = diffFileOf(context)
        if (diffFile != null) return ToggleDiffPlan.of(true, diffFile.sourcePath, false, false, null)
        val file = editedFileOf(context) ?: return ToggleDiffTarget.None
        val changes = project.service<HgChangesService>()
        val loaded = changes.changesLoaded()
        val tabKey = if (loaded && changes.hasDiff(file)) changes.diffTabKeyOf(file) else null
        return ToggleDiffPlan.of(false, null, true, loaded, tabKey)
    }

    fun editedFileOf(context: DataContext): VirtualFile? {
        if (CommonDataKeys.EDITOR.getData(context) == null) return null
        val file = CommonDataKeys.VIRTUAL_FILE.getData(context) ?: return null
        if (file.isDirectory || !file.isInLocalFileSystem) return null
        return file
    }

    fun diffFileOf(context: DataContext): HgDiffVirtualFile? {
        (CommonDataKeys.VIRTUAL_FILE.getData(context) as? HgDiffVirtualFile)?.let { return it }
        return PlatformCoreDataKeys.FILE_EDITOR.getData(context)?.file as? HgDiffVirtualFile
    }

    fun caretLineOf(context: DataContext): Int? =
        CommonDataKeys.EDITOR.getData(context)?.caretModel?.logicalPosition?.line
}
