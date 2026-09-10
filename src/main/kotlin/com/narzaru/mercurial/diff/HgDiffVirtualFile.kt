package com.narzaru.mercurial.diff

import com.narzaru.mercurial.changes.HgDiffProcessor
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.impl.DiffSettingsHolder.DiffSettings
import com.intellij.openapi.project.Project
import java.util.Collections

class HgDiffVirtualFile(chain: DiffRequestChain, name: String, val sourcePath: String?) :
    ChainDiffVirtualFile(chain, name) {

    private val processors = Collections.synchronizedList(ArrayList<HgDiffProcessor>())

    override fun isIncludedInDocumentHistory(project: Project): Boolean =
        DiffHistoryScope.keepsPlaces(DiffSettings.getSettings().isIncludedInNavigationHistory)

    override fun createProcessor(project: Project): DiffRequestProcessor {
        val processor = HgDiffProcessor(project, chain)
        processors.add(processor)
        return processor
    }

    fun reload() {
        livingProcessors().forEach { it.reload() }
    }

    fun rightCaretLine(): Int? = livingProcessors().firstNotNullOfOrNull { it.rightCaretLine() }

    fun scrollToRightLine(line: Int): Boolean =
        livingProcessors().map { it.scrollToRightLine(line) }.any { it }

    private fun livingProcessors(): List<HgDiffProcessor> = synchronized(processors) {
        processors.removeAll { it.isDisposed }
        ArrayList(processors)
    }
}
