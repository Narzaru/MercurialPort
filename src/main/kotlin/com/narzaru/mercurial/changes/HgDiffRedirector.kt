package com.narzaru.mercurial.changes

import com.narzaru.mercurial.diff.HgDiffTabManager
import com.narzaru.mercurial.hg.HgPaths
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class HgDiffRedirector(private val project: Project) {

    fun redirect(opening: OpeningFile) {
        val decision = DiffRedirectPolicy.decide(SignalsOf(project, opening))
        if (decision !is DiffRedirectDecision.ShowDiff) return
        project.service<HgChangesService>()
            .openDiffTab(opening.file.path, decision.tabKey, null, decision.caretLine)
    }

    class OpeningFile(
        val file: VirtualFile,
        val previousFile: VirtualFile?,
        val restoredBySession: Boolean,
        val caretLine: Int?
    )

    private class SignalsOf(
        private val project: Project,
        private val opening: OpeningFile
    ) : DiffRedirectSignals {

        override fun fileIsEligible(): Boolean =
            !opening.file.isDirectory && opening.file.isInLocalFileSystem

        override fun tabRestoredBySession(): Boolean = opening.restoredBySession

        override fun openedAsFileOnPurpose(): Boolean =
            project.service<HgChangesService>().takeOpenedAsFile(opening.file.path)

        override fun cameFromItsDiff(): Boolean {
            val previous = opening.previousFile ?: return false
            val source = project.service<HgDiffTabManager>().sourcePathOf(previous) ?: return false
            return HgPaths.key(source) == HgPaths.key(opening.file.path)
        }

        override fun redirectEnabled(): Boolean = ChangesSettings(project).openDiffInsteadOfFile

        override fun diffTabKey(): String? {
            val changes = project.service<HgChangesService>()
            if (!changes.hasDiff(opening.file)) return null
            return changes.diffTabKeyOf(opening.file)
        }

        override fun caretLine(): Int? = opening.caretLine
    }
}
