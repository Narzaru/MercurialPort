package com.narzaru.mercurial.changes

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager

class HgDiffRedirectArming : AnActionListener {

    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        val project = event.project ?: return
        if (project.isDisposed) return
        val changes = project.service<HgChangesService>()
        val diffSourcePath = changes.selectedDiffSourcePath() ?: return
        val actionId = event.actionManager.getId(action)
        val redirectEnabled = ChangesSettings(project).openDiffInsteadOfFile
        LOG.info(
            "${HgDiffRedirect.TRACE} action in diff id=$actionId class=${action.javaClass.simpleName}" +
                " enabled=$redirectEnabled source=$diffSourcePath" +
                " fileEditor=${contextClass(event, PlatformCoreDataKeys.FILE_EDITOR)}" +
                " virtualFile=${contextClass(event, CommonDataKeys.VIRTUAL_FILE)}"
        )
        if (!NavigationArming.armsRedirect(actionId, diffSourcePath, redirectEnabled)) return
        val openPaths = FileEditorManager.getInstance(project).openFiles.map { it.path }
        changes.inlineDiffArming.arm(diffSourcePath, openPaths)
        LOG.info(
            "${HgDiffRedirect.TRACE} arm action=$actionId source=$diffSourcePath openBefore=${openPaths.size}"
        )
    }

    private fun contextClass(event: AnActionEvent, key: DataKey<*>): String =
        event.dataContext.getData(key)?.javaClass?.simpleName ?: "none"

    private companion object {
        val LOG = Logger.getInstance(HgDiffRedirectArming::class.java)
    }
}
