package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgContentCache
import com.narzaru.mercurial.hg.HgFailure
import com.narzaru.mercurial.hg.HgPatchApplier
import com.narzaru.mercurial.model.HgFileItem

interface LineComparator {

    fun changedLines(left: String, right: String): List<DiffFragment>

    fun fragmentLines(left: String, right: String): List<DiffFragment>
}

class FragmentSettings(
    val widenToFragments: Boolean,
    val fragmentsFromHg: Boolean,
    val stamp: String
)

class BranchBase(val text: String?, val skippedHunks: Int? = null)

class BranchBaseBuilder(
    private val commands: HgCommands,
    private val cache: HgContentCache,
    private val state: ComparisonState,
    private val fragments: FragmentSettings,
    private val comparator: LineComparator,
    private val warn: (String) -> Unit
) {

    fun readRevision(rev: String, path: String): String? {
        val key = HgContentCache.key(rev, path)
        cache.get(key)?.let { return it.text }
        val res = commands.run("cat", "-r", rev, path)
        if (!res.success) {
            warn("hg cat -r $rev $path failed: ${HgFailure.message(res)}")
        }
        val text = if (res.success) res.stdout else null
        cache.put(key, text)
        return text
    }

    fun read(item: HgFileItem, head: String?): BranchBase {
        val revs = state.revsFor(item.path, item.basePath)
        if (head == null || revs.isNullOrEmpty() || revs.size == 1) {
            return BranchBase(readRevision(item.baseRev.ifEmpty { state.baseRev }, item.basePath))
        }

        val key = HgContentCache.key(
            "before:${fragments.stamp}(${revs.joinToString("+")})", item.path
        )
        cache.get(key)?.let { return BranchBase(it.text) }

        var lines = head.split("\n")
        var skipped = 0
        val names = namesOf(item)
        for (rev in revs.asReversed()) {
            val res = commands.run(listOf("diff", "--git", "-c", rev) + names.map { "path:$it" })
            if (!res.success) {
                warn("hg diff -c $rev failed: ${HgFailure.message(res)}")
                return BranchBase(null)
            }
            val patch = res.stdout
            if (patch.isBlank()) continue
            val applied = HgPatchApplier.applyReverse(lines, HgPatchApplier.parseHunks(patch, names))
            lines = applied.lines
            skipped += applied.skipped
        }
        val text = widen(item, head, lines) ?: lines.joinToString("\n")
        cache.put(key, text)
        return BranchBase(text, skipped)
    }

    private fun widen(item: HgFileItem, head: String, rolledBack: List<String>): String? {
        if (!state.mode.isChangesets || !fragments.widenToFragments) return null
        if (DiffSidesPlan.isNew(item)) return null
        val baseRev = item.baseRev.ifEmpty { state.baseRev }
        val baseText = readRevision(baseRev, item.basePath) ?: return null
        val own = comparator.changedLines(rolledBack.joinToString("\n"), head).map { it.right }
        if (own.isEmpty()) return null

        val scope = if (fragments.fragmentsFromHg) {
            hgFragments(item, baseRev) ?: return null
        } else {
            comparator.fragmentLines(baseText, head)
        }
        return FragmentScope
            .leftSide(baseText.split("\n"), head.split("\n"), scope, own)
            .joinToString("\n")
    }

    private fun hgFragments(item: HgFileItem, baseRev: String): List<DiffFragment>? {
        val names = namesOf(item)
        val res = commands.run(
            listOf("diff", "--git", "-U", "0", "--rev", baseRev, "--rev", item.headRev) +
                names.map { "path:$it" }
        )
        if (!res.success) {
            warn("hg diff --rev $baseRev --rev ${item.headRev} failed: ${HgFailure.message(res)}")
            return null
        }
        return HgPatchApplier.parseHunks(res.stdout, names).map {
            DiffFragment(lineRange(it.oldStart, it.oldLines.size), lineRange(it.newStart, it.newLines.size))
        }
    }

    private fun namesOf(item: HgFileItem): List<String> =
        listOfNotNull(item.path, item.copiedFrom.ifEmpty { null })

    private fun lineRange(start: Int, size: Int): LineRange =
        if (size == 0) LineRange(start, start) else LineRange(start - 1, start - 1 + size)
}
