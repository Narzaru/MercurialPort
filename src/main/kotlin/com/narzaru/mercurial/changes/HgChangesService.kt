package com.narzaru.mercurial.changes

import com.narzaru.mercurial.diff.HgDiffTabManager
import com.narzaru.mercurial.hg.HgPaths
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Component
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class HgChangesService(val project: Project) {

    @Volatile
    var panel: HgChangesPanel? = null

    private val pendingCarets = ConcurrentHashMap<String, DiffCaret>()

    fun takePendingCaret(path: String): DiffCaret? = pendingCarets.remove(HgPaths.key(path))

    private val requestedDiff = AtomicReference<RequestedDiff?>()

    fun diffSettingsChanged(changesetsOnly: Boolean) {
        panel?.reloadForSettings(changesetsOnly)
    }

    fun branchLimitChanged() {
        panel?.reloadForBranchLimit()
    }

    fun diffTabKeyOf(file: VirtualFile): String? = panel?.diffTabKeyOf(file)

    fun changesLoaded(): Boolean = panel?.isLoaded() == true

    fun requestDiff(path: String, caret: DiffCaret?) {
        requestedDiff.set(RequestedDiff(path, caret))
        panel?.loadForRequestedDiff()
    }

    fun takeRequestedDiff(): RequestedDiff? = requestedDiff.getAndSet(null)

    class RequestedDiff(val path: String, val caret: DiffCaret?)

    fun openDiffTab(
        path: String,
        key: String,
        focusBack: Component?,
        caret: DiffCaret? = null,
        requestFocus: Boolean = false
    ) {
        if (caret != null) pendingCarets[HgPaths.key(path)] = caret
        val tabs = project.service<HgDiffTabManager>()
        tabs.openTab(key, File(path).name, path, focusBack, requestFocus) {
            SimpleDiffRequestChain.fromProducer(diffProducerFor(path))
        }
        if (caret != null && tabs.placeCaretInShownChangesTab(key, caret)) takePendingCaret(path)
    }

    fun reloadOpenDiffs() {
        project.service<HgDiffTabManager>().reloadOpenTabs()
    }

    private fun diffProducerFor(path: String): DiffRequestProducer = object : DiffRequestProducer {
        override fun getName(): String = path

        override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest =
            panel?.let { it.diffSource.request(it.comparison, path) }
                ?: throw DiffRequestProducerException("No Hg changes for ${File(path).name}")
    }

    companion object {
        const val TOOL_WINDOW_ID = "Hg Changes"
    }
}
