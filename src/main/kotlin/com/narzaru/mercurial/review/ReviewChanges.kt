package com.narzaru.mercurial.review

import com.narzaru.mercurial.changes.BranchRevisions
import com.narzaru.mercurial.changes.HgCommands
import com.narzaru.mercurial.changes.ParallelJobs
import com.narzaru.mercurial.hg.HgFailure
import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.hg.HgStatusParser
import java.util.concurrent.ConcurrentHashMap

class ReviewOptions(
    val baseBranch: String = "default",
    val renameSimilarity: Double = 0.6
)

enum class ReviewChangeType { ADDED, DELETED, MODIFIED, RENAMED, COPIED }

data class ReviewFile(
    val path: String,
    val oldPath: String,
    val type: ReviewChangeType,
    val baseRev: String?,
    val revisions: List<String>,
    val binary: Boolean,
    val names: List<String> = listOf(oldPath, path).distinct()
) {

    val headRev: String get() = revisions.last()

    val oldRev: String? get() = baseRev.takeIf { type != ReviewChangeType.ADDED }

    val newRev: String? get() = headRev.takeIf { type != ReviewChangeType.DELETED }
}

class ReviewLoad(
    val revisions: List<ReviewRevision>,
    val files: List<ReviewFile>,
    val error: String? = null
) {

    val nodes: Set<String> = revisions.mapTo(LinkedHashSet()) { it.node }

    val byNode: Map<String, ReviewRevision> = revisions.associateBy { it.node }
}

class ReviewFileDiff(
    val file: ReviewFile,
    val oldLines: List<String>,
    val newLines: List<String>,
    val hunks: List<ReviewHunk>,
    val steps: List<ReviewStep> = emptyList(),
    val attribution: ReviewAttribution? = null
) {

    val shownHunks: List<ReviewHunk> get() = hunks.filter { it.shown }

    fun leftSide(): List<String> = ReviewHunks.leftSide(oldLines, newLines, hunks)
}

class ReviewChanges(
    private val commands: HgCommands,
    private val options: ReviewOptions = ReviewOptions(),
    private val jobs: ParallelJobs? = null,
    private val lineDiff: ReviewLineDiff = ReviewLineDiff()
) {

    private val contents = ConcurrentHashMap<Pair<String, String>, Content>()

    private val graph = ConcurrentHashMap<String, ReviewRevision>()

    private val graphRanges = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    private val changedFromFirstParent = ConcurrentHashMap<String, Set<String>>()

    fun load(nodes: Collection<String>): ReviewLoad {
        if (nodes.isEmpty()) return ReviewLoad(emptyList(), emptyList())
        return loadRevset(BranchRevisions.revset(nodes))
    }

    fun loadRevset(revset: String): ReviewLoad {
        val log = commands.run(ReviewLog.args(revset))
        if (!log.success) return ReviewLoad(emptyList(), emptyList(), HgFailure.message(log))
        val revisions = ReviewLog.parse(log.stdout).sortedBy { it.rev }
        revisions.forEach { graph[it.node] = it }

        val merges = revisions.filter { it.isMerge }
        val mergeTouches = HashMap<String, Set<String>>()
        for ((node, touched) in parallel(merges) { it.node to mergeTouches(it) }) {
            touched ?: return ReviewLoad(revisions, emptyList(), "hg status of merge $node failed")
            mergeTouches[node] = touched
        }
        val touches = revisions.map { ReviewTouches(it.node, if (it.isMerge) mergeTouches.getValue(it.node) else it.touchedPaths()) }

        val byNode = revisions.associateBy { it.node }
        val entries = ReviewFileSet.entries(revisions).flatMap { splitWeakRenames(it, byNode) }
        val files = parallel(entries) { file(it, touches, byNode) }.filterNotNull()
        return ReviewLoad(revisions, files.sortedBy { it.path })
    }

    fun diff(load: ReviewLoad, file: ReviewFile): ReviewFileDiff = diffs(load, listOf(file)).single()

    fun diffs(load: ReviewLoad, files: List<ReviewFile>): List<ReviewFileDiff> {
        fetchGraph(files.mapNotNull { file -> traceRange(load, file) })
        return parallel(files) { diffOf(load, it) }
    }

    fun needsTrace(load: ReviewLoad, file: ReviewFile): Boolean = traceRange(load, file) != null

    private fun traceRange(load: ReviewLoad, file: ReviewFile): Pair<String, String>? {
        val base = file.baseRev ?: return null
        if (file.binary || ReviewChain.insideReview(load.byNode, base, file.headRev)) return null
        return base to file.headRev
    }

    private fun diffOf(load: ReviewLoad, file: ReviewFile): ReviewFileDiff {
        val oldLines = file.oldRev?.let { ReviewText.lines(read(it, file.oldPath)) }.orEmpty()
        val newLines = file.newRev?.let { ReviewText.lines(read(it, file.path)) }.orEmpty()
        if (file.binary) return ReviewFileDiff(file, oldLines, newLines, emptyList())
        val fragments = lineDiff.compare(oldLines, newLines)
        val plain = ReviewFileDiff(file, oldLines, newLines, ReviewHunks.of(fragments, emptyList(), null))
        val (from, to) = traceRange(load, file) ?: return plain
        val chain = ReviewChain.walk(graph, load.nodes, from, to) ?: return plain
        val steps = steps(load, chain, file, oldLines)
        val attribution = ReviewReplay.replay(oldLines, steps, lineDiff)
        return ReviewFileDiff(file, oldLines, newLines, ReviewHunks.of(fragments, steps, attribution), steps, attribution)
    }

    private fun steps(load: ReviewLoad, chain: List<String>, file: ReviewFile, oldLines: List<String>): List<ReviewStep> {
        val names = file.names.toHashSet()
        val steps = ArrayList<ReviewStep>()
        var path = file.oldPath
        var content = oldLines
        for ((prev, node) in chain.zipWithNext()) {
            val revision = graph[node] ?: continue
            if (revision.isMerge) {
                if (prev == revision.p1 && changedFromFirstParent[node]?.contains(path) == false) continue
                val lines = ReviewText.lines(read(node, path))
                if (lines == content) continue
                val other = revision.parents.firstOrNull { it != prev }
                val differsFromOther = other == null || ReviewText.lines(read(other, path)) != lines
                steps.add(ReviewStep(node, ReviewStep.kindOf(revision, load.nodes), lines, differsFromOther))
                content = lines
                continue
            }
            val renamed = revision.changes.firstOrNull { it.copiedFrom == path && it.path in names }
            if (renamed != null) path = renamed.path
            else if (revision.changes.none { it.path == path }) continue
            val lines = ReviewText.lines(read(node, path))
            steps.add(ReviewStep(node, ReviewStep.kindOf(revision, load.nodes), lines))
            content = lines
        }
        return steps
    }

    private fun splitWeakRenames(entry: ReviewEntry, byNode: Map<String, ReviewRevision>): List<ReviewEntry> {
        val weak = entry.renames.indexOfFirst { similarity(it, byNode) < options.renameSimilarity }
        if (weak < 0) return listOf(entry)
        return ReviewFileSet.split(entry, weak).flatMap { splitWeakRenames(it, byNode) }
    }

    private fun similarity(rename: ReviewRename, byNode: Map<String, ReviewRevision>): Double {
        val before = byNode[rename.node]?.p1?.let { read(it, rename.from) } ?: return 0.0
        val after = read(rename.node, rename.to) ?: return 0.0
        return lineDiff.similarity(ReviewText.lines(before), ReviewText.lines(after))
    }

    private fun file(entry: ReviewEntry, touches: List<ReviewTouches>, byNode: Map<String, ReviewRevision>): ReviewFile? {
        val revisions = ReviewFileSet.touching(entry.names, touches)
        if (revisions.isEmpty()) return null
        val base = byNode[revisions.first()]?.p1
        val old = base?.let { read(it, entry.oldPath) }
        val new = read(revisions.last(), entry.path)
        if (old == null && new == null) return null
        if (old != null && new != null && ReviewText.lines(old) == ReviewText.lines(new)) return null
        val type = when {
            old == null -> ReviewChangeType.ADDED
            new == null -> ReviewChangeType.DELETED
            entry.oldPath == entry.path -> ReviewChangeType.MODIFIED
            entry.copy == ReviewCopy.COPY -> ReviewChangeType.COPIED
            else -> ReviewChangeType.RENAMED
        }
        return ReviewFile(
            path = entry.path,
            oldPath = entry.oldPath,
            type = type,
            baseRev = base,
            revisions = revisions,
            binary = ReviewText.isBinary(old) || ReviewText.isBinary(new),
            names = entry.names
        )
    }

    private fun mergeTouches(merge: ReviewRevision): Set<String>? {
        val fromP1 = changedFrom(merge.p1 ?: return null, merge.node) ?: return null
        changedFromFirstParent[merge.node] = fromP1.toHashSet()
        if (!ReviewFileSet.comparesSecondParent(merge, options.baseBranch)) return ReviewFileSet.mergeTouches(fromP1, null)
        val fromP2 = changedFrom(merge.p2 ?: return null, merge.node) ?: return null
        return ReviewFileSet.mergeTouches(fromP1, fromP2)
    }

    private fun changedFrom(parent: String, node: String): List<String>? {
        val res = commands.run("status", "--rev", parent, "--rev", node, HgStatusParser.statusFlags(false))
        if (!res.success) return null
        return HgStatusParser.parse(res.stdout).map { HgPaths.normalize(it.path) }
    }

    private fun fetchGraph(ranges: List<Pair<String, String>>) {
        val missing = ranges.distinct().filterNot { it in graphRanges }
        if (missing.isEmpty()) return
        val revset = missing.joinToString("+") { (from, to) -> "($from::$to)" }
        val log = commands.run(ReviewLog.args(revset))
        if (!log.success) return
        ReviewLog.parse(log.stdout).forEach { graph[it.node] = it }
        graphRanges.addAll(missing)
    }

    private fun read(rev: String, path: String): String? =
        contents.getOrPut(rev to path) {
            val res = commands.run("cat", "-r", rev, "path:$path")
            Content(if (res.success) res.stdout else null)
        }.text

    private fun <K, V> parallel(keys: List<K>, work: (K) -> V): List<V> =
        jobs?.map(keys, work) ?: keys.map(work)

    private class Content(val text: String?)
}
