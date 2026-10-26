package com.narzaru.mercurial.changes

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class HgDiffRedirector(private val project: Project) {

    fun decisionFor(file: VirtualFile): DiffRedirectDecision {
        val signals = SignalsOf(project, file)
        val decision = DiffRedirectPolicy.decide(signals)
        if (signals.redirectEnabled()) {
            val editors = FileEditorManager.getInstance(project).getEditorList(file).size
            LOG.info(
                "${HgDiffRedirect.TRACE} accept file=${file.path} decision=$decision" +
                    " ${signals.trace()} editors=$editors"
            )
        }
        return decision
    }

    private class SignalsOf(
        private val project: Project,
        private val file: VirtualFile
    ) : DiffRedirectSignals {

        private val jump: InlineDiffArming.Jump? by lazy { changes().inlineDiffArming.peek() }

        private val enabled: Boolean by lazy { ChangesSettings(project).openDiffInsteadOfFile }

        private val armed: Boolean by lazy { jump != null }

        private val eligible: Boolean by lazy { !file.isDirectory && file.isInLocalFileSystem }

        private val openedBefore: Boolean by lazy { jump?.wasOpenBefore(file.path) == true }

        private val onPurpose: Boolean by lazy { changes().peekOpenedAsFile(file.path) }

        private val sameFile: Boolean by lazy {
            DiffNavigationOrigin.cameFromItsDiff(jump?.diffSourcePath, file.path)
        }

        private val hasDiff: Boolean by lazy { changes().hasDiff(file) }

        override fun redirectEnabled(): Boolean = enabled

        override fun startedFromDiff(): Boolean = armed

        override fun fileIsEligible(): Boolean = eligible

        override fun fileWasOpenBeforeJump(): Boolean = openedBefore

        override fun openedAsFileOnPurpose(): Boolean = onPurpose

        override fun cameFromItsDiff(): Boolean = sameFile

        override fun fileHasDiff(): Boolean = hasDiff

        fun trace(): String =
            "enabled=$enabled armed=$armed eligible=$eligible openedBefore=$openedBefore" +
                " onPurpose=$onPurpose sameFile=$sameFile hasDiff=$hasDiff"

        private fun changes(): HgChangesService = project.service()
    }

    private companion object {
        val LOG = Logger.getInstance(HgDiffRedirector::class.java)
    }
}
