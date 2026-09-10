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

    var panel: HgChangesPanel? = null

    private val pendingLines = ConcurrentHashMap<String, Int>()

    fun setPendingLine(path: String, line: Int) {
        if (line >= 0) pendingLines[HgPaths.key(path)] = line else pendingLines.remove(HgPaths.key(path))
    }

    fun takePendingLine(path: String): Int? = pendingLines.remove(HgPaths.key(path))

    private val openedAsFile = ConcurrentHashMap<String, Long>()

    private val requestedDiff = AtomicReference<RequestedDiff?>()

    fun openAsFile(path: String) {
        openedAsFile[HgPaths.key(path)] = System.currentTimeMillis() + OPEN_AS_FILE_DEADLINE_MS
    }

    fun takeOpenedAsFile(path: String): Boolean {
        val deadline = openedAsFile.remove(HgPaths.key(path)) ?: return false
        return deadline >= System.currentTimeMillis()
    }

    fun diffSettingsChanged(changesetsOnly: Boolean) {
        panel?.reloadForSettings(changesetsOnly)
    }

    fun hasDiff(file: VirtualFile): Boolean = panel?.hasDiffFor(file) == true

    fun diffTabKeyOf(file: VirtualFile): String? = panel?.diffTabKeyOf(file)

    fun changesLoaded(): Boolean = panel?.isLoaded() == true

    fun requestDiff(path: String, caretLine: Int?) {
        requestedDiff.set(RequestedDiff(path, caretLine))
    }

    fun takeRequestedDiff(): RequestedDiff? = requestedDiff.getAndSet(null)

    class RequestedDiff(val path: String, val caretLine: Int?)

    fun openDiffTab(
        path: String,
        key: String,
        focusBack: Component?,
        caretLine: Int? = null,
        requestFocus: Boolean = false
    ) {
        if (caretLine != null) setPendingLine(path, caretLine)
        val tabs = project.service<HgDiffTabManager>()
        tabs.openTab(key, File(path).name, path, focusBack, requestFocus) {
            SimpleDiffRequestChain.fromProducer(diffProducerFor(path))
        }
        if (caretLine != null && tabs.scrollShownChangesTab(key, caretLine)) takePendingLine(path)
    }

    fun reloadOpenDiffs() {
        project.service<HgDiffTabManager>().reloadOpenTabs()
    }

    private fun diffProducerFor(path: String): DiffRequestProducer = object : DiffRequestProducer {
        override fun getName(): String = path

        override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest =
            panel?.buildDiffRequestForPath(path)
                ?: throw DiffRequestProducerException("No Hg changes for ${File(path).name}")
    }

    companion object {
        const val TOOL_WINDOW_ID = "Hg Changes"

        private const val OPEN_AS_FILE_DEADLINE_MS = 2000L
    }
}
