package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgCommandRunner
import com.narzaru.mercurial.hg.HgContentCache
import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.hg.HgSettings
import com.narzaru.mercurial.model.ChangesetsFragments
import com.narzaru.mercurial.model.HgFileItem
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ChangesDiffSource(
    private val project: Project,
    private val commandsAt: (File) -> HgCommands = { HgCommands.of(HgCommandRunner(it)) }
) {

    private val prefetchId = AtomicInteger()

    private val baseContentCache = HgContentCache()

    private val skippedHunks = ConcurrentHashMap<String, Int>()

    private val lineComparator = object : LineComparator {
        override fun changedLines(left: String, right: String): List<DiffFragment> =
            compareLines(left, right, ComparisonPolicy.DEFAULT)

        override fun fragmentLines(left: String, right: String): List<DiffFragment> =
            compareLines(left, right, fragmentPolicy())
    }

    fun request(state: ComparisonState, path: String): SimpleDiffRequest? {
        val repoRoot = state.repoRoot ?: return null
        val item = changedItem(state, path) ?: return null
        val sides = readerFor(repoRoot, state).read(item, state.baseRev)
        return buildRequest(item, File(repoRoot, item.path), sides)
    }

    fun prefetch(state: ComparisonState, items: List<HgFileItem>) {
        val repoRoot = state.repoRoot ?: return
        if (items.isEmpty()) return
        val requestId = prefetchId.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val reader = readerFor(repoRoot, state)
            for (item in items) {
                if (requestId != prefetchId.get()) return@executeOnPooledThread
                reader.read(item, state.baseRev)
            }
        }
    }

    fun cancelPrefetch() {
        prefetchId.incrementAndGet()
    }

    fun invalidate() {
        cancelPrefetch()
        baseContentCache.clear()
        skippedHunks.clear()
    }

    private fun readerFor(repoRoot: File, state: ComparisonState): DiffSidesReader {
        val builder = BranchBaseBuilder(
            commandsAt(repoRoot),
            baseContentCache,
            state,
            FragmentSettings(
                HgSettings.widenToFragments,
                HgSettings.changesetsFragments == ChangesetsFragments.HG,
                changesetsStamp()
            ),
            lineComparator,
            LOG::warn
        )
        return DiffSidesReader(builder, skippedHunks)
    }

    private fun buildRequest(item: HgFileItem, localFile: File, sides: DiffSides): SimpleDiffRequest? {
        val base = sides.base
        val head = sides.head
        val factory = DiffContentFactory.getInstance()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(localFile.name)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(localFile)
        val fromRevision = sides.plan.rightIsRevision

        if (base == null && head == null && vf == null) return null

        val rightIsLocalFile = if (fromRevision) vf != null && head != null && localMatches(vf, head)
        else vf != null
        val baseContent = if (base != null) revisionContent(base, vf, fileType) else factory.createEmpty()
        val rightContent = when {
            fromRevision -> when {
                head == null -> factory.createEmpty()
                rightIsLocalFile -> factory.create(project, vf!!)
                else -> revisionContent(head, vf, fileType)
            }
            vf != null -> factory.create(project, vf)
            else -> factory.createEmpty()
        }
        val baseTitle = DiffTitles.left(base != null, fromRevision, sides.onlyLast, sides.baseTitleRev, item.copiedFrom)
        val rightTitle = DiffTitles.right(
            fromRevision, sides.onlyLast, head != null, rightIsLocalFile, vf != null,
            item.headRev, sides.skippedHunks
        )

        val request = SimpleDiffRequest(localFile.name, baseContent, rightContent, baseTitle, rightTitle)
        request.putUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE, Side.RIGHT)
        project.service<HgChangesService>().takePendingCaret(localFile.path)?.let {
            HgDiffProcessor.requestRightCaret(request, it)
        }
        return request
    }

    private fun localMatches(file: VirtualFile, text: String): Boolean {
        val document = runReadAction { FileDocumentManager.getInstance().getDocument(file) }
        if (document == null) {
            LOG.info("Hg diff: no document for ${file.path}, right side falls back to the revision")
            return false
        }
        val local = runReadAction { document.text }
        if (local == text) return true
        LOG.info(
            "Hg diff: ${file.path} differs from the revision, right side falls back to it. " +
                TextMismatch.describe(local, text)
        )
        return false
    }

    private fun revisionContent(text: String, file: VirtualFile?, fileType: FileType) =
        if (file != null) DiffContentFactory.getInstance().create(project, text, file)
        else DiffContentFactory.getInstance().create(project, text, fileType)

    companion object {

        private val LOG = Logger.getInstance(ChangesDiffSource::class.java)

        fun changedItem(state: ComparisonState, path: String): HgFileItem? {
            val repoRoot = state.repoRoot ?: return null
            val key = HgPaths.keyRelativeTo(path, repoRoot) ?: return null
            return state.changedItemAt(key)
        }

        fun changesetsStamp(): String = ComparisonSignature.fragmentsStamp(
            HgSettings.widenToFragments, HgSettings.changesetsFragments, fragmentPolicy().name
        )

        private fun fragmentPolicy(): ComparisonPolicy = when (HgSettings.changesetsFragments) {
            ChangesetsFragments.PLATFORM_TRIMMED -> ComparisonPolicy.TRIM_WHITESPACES
            ChangesetsFragments.PLATFORM_SETTINGS -> viewerPolicy()
            else -> ComparisonPolicy.DEFAULT
        }

        private fun viewerPolicy(): ComparisonPolicy =
            TextDiffSettingsHolder.TextDiffSettings.getSettings().ignorePolicy.comparisonPolicy

        private fun compareLines(left: String, right: String, policy: ComparisonPolicy): List<DiffFragment> =
            ComparisonManager.getInstance()
                .compareLines(left, right, policy, DumbProgressIndicator.INSTANCE)
                .map {
                    DiffFragment(
                        LineRange(it.startLine1, it.endLine1),
                        LineRange(it.startLine2, it.endLine2)
                    )
                }
    }
}
