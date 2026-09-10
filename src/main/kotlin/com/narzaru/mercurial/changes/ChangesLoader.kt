package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgFailure
import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.hg.HgResult
import com.narzaru.mercurial.hg.HgStatusParser
import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem

class ChangesResult(
    val files: List<HgFileItem>,
    val targetRev: String,
    val statusText: String,
    val error: String?,

    val branch: String = "",
    val revisions: List<HgRevision> = emptyList(),
    val selection: RevisionSelection = RevisionSelection.DEFAULT,

    val statsRanges: List<DiffRange> = emptyList(),

    val revsByFile: Map<String, List<String>> = emptyMap(),

    val statsPaths: Map<DiffRange, List<String>> = emptyMap()
)

class ChangesLoader(
    private val commands: HgCommands,
    private val jobs: ParallelJobs,
    private val revisionOverrides: (String) -> List<String>,
    private val warn: (String) -> Unit
) {

    fun load(
        mode: HgDisplayMode,
        customBranch: String,
        untracked: Boolean,
        trace: LoadTrace? = null
    ): ChangesResult {
        val usesRevisions = mode.usesRevisions
        val earlyTargetRev = when (mode) {
            HgDisplayMode.CUSTOM_BRANCH -> customBranch
            HgDisplayMode.MERGE -> BranchRevisions.mergeBaseRevset(customBranch)
            HgDisplayMode.UNCOMMITTED -> "."
            else -> null
        }
        val head = headQueries(usesRevisions, earlyTargetRev, trace)
        val branch = head.branch
        val revisions = head.revisions
        val selection = if (usesRevisions) {
            RevisionSelection.fromStorage(revisionOverrides(branch))
        } else {
            RevisionSelection.DEFAULT
        }
        val selectedNodes = selection.selectedNodes(revisions)

        if (usesRevisions && selectedNodes.isEmpty()) {
            val text = if (revisions.isEmpty()) {
                "No revisions of this branch found. Use '${HgDisplayMode.UNCOMMITTED.title}'."
            } else {
                "No revisions selected — pick at least one below."
            }
            return ChangesResult(emptyList(), ".", "", text, branch, revisions, selection)
        }

        val targetRev = earlyTargetRev ?: BranchRevisions.baseRevset(selectedNodes)

        val currentInfo = head.currentInfo
        val baseInfo = head.baseInfo
            ?: trace.timed("base revision info") { revisionInfo(targetRev) }
        val statusText = StatusTextFormatter.branchInfo(mode, currentInfo, baseInfo)

        if (mode == HgDisplayMode.MERGE) {
            StatusTextFormatter.mergeBaseProblem(customBranch, currentInfo, baseInfo)?.let {
                return ChangesResult(emptyList(), ".", "", it, branch, revisions, selection)
            }
        }

        if (mode.isChangesets) {
            return reviewChanges(selectedNodes, targetRev, statusText, branch, revisions, selection, trace)
        }

        val scope = if (mode == HgDisplayMode.BRANCH) {
            trace.timed("branch own files") { branchOwnFiles(selectedNodes) }
        } else {
            null
        }

        val statusRes = trace.timed("status") {
            commands.run(
                "status", "--rev", targetRev,
                HgStatusParser.statusFlags(untracked), HgStatusParser.COPIES_FLAG
            )
        }
        if (!statusRes.success) {
            return ChangesResult(
                emptyList(), targetRev, "", StatusTextFormatter.statusError(mode, statusRes), branch, revisions, selection
            )
        }

        val all = HgStatusParser.foldRenames(HgStatusParser.parse(statusRes.stdout))
        val files = if (scope == null) all else all.filter { it.status == HgStatusParser.UNTRACKED_STATUS || inScope(it, scope) }
        val range = DiffRange(targetRev, "")
        val statsPaths =
            if (scope == null) emptyMap() else DiffStatsPlan.pathsByRange(listOf(range), files, targetRev)
        return ChangesResult(
            files, targetRev, statusText, null, branch, revisions, selection,
            listOf(range), emptyMap(), statsPaths
        )
    }

    private fun reviewChanges(
        selectedNodes: List<String>,
        targetRev: String,
        statusText: String,
        branch: String,
        revisions: List<HgRevision>,
        selection: RevisionSelection,
        trace: LoadTrace? = null
    ): ChangesResult {
        val log = trace.timed("changeset log") {
            commands.run(
                "log", "-r", BranchRevisions.revset(selectedNodes),
                "--template", BranchRevisions.FILES_TEMPLATE
            )
        }
        if (!log.success) {
            return ChangesResult(
                emptyList(), targetRev, "", HgFailure.message(log), branch, revisions, selection
            )
        }

        val ownFiles = trace.timed("parse changeset log") { BranchRevisions.parseFileTouches(log.stdout) }
        val listed = ownFiles.values.flatMapTo(HashSet()) { paths -> paths.map { HgPaths.key(it) } }
        val touches = LinkedHashMap(ownFiles)
        val merges = revisions.filter { it.isMerge && it.node in selectedNodes }
        trace.timed("merge touches") {
            jobs.map(merges) { merge -> merge.node to mergeTouches(merge.node) }
                .forEach { (node, paths) -> if (paths != null) touches[node] = paths }
        }

        val statusCache = HashMap<DiffRange, List<HgFileItem>>()
        var ranges = trace.timed("file ranges") { BranchRevisions.fileRanges(selectedNodes, touches, listed) }
        prefetchSingleRevisions(ranges, revisions, statusCache, trace)
        var collected = collectRangeFiles(ranges, statusCache, trace, "range files")
        collected.failure?.let {
            return ChangesResult(
                emptyList(), targetRev, "", StatusTextFormatter.statusError(HgDisplayMode.CHANGESETS, it),
                branch, revisions, selection
            )
        }
        BranchRevisions.mergeRenames(ranges, collected.renames, selectedNodes)?.let { joined ->
            ranges = joined
            collected = collectRangeFiles(joined, statusCache, trace, "range files after renames")
            collected.failure?.let {
                return ChangesResult(
                    emptyList(), targetRev, "", StatusTextFormatter.statusError(HgDisplayMode.CHANGESETS, it),
                    branch, revisions, selection
                )
            }
        }

        val revsByFile = HashMap<String, List<String>>()
        for ((fileKey, range) in ranges) revsByFile[fileKey] = range.revs

        val files = collected.files.distinctBy { HgPaths.key(it.path) }
        val statsPaths = trace.timed("stats plan") {
            DiffStatsPlan.pathsByRange(collected.ranges, files, targetRev)
        }
        return ChangesResult(
            files, targetRev, statusText, null, branch, revisions, selection,
            collected.ranges, revsByFile, statsPaths
        )
    }

    private class RangeFiles(
        val files: List<HgFileItem> = emptyList(),
        val ranges: List<DiffRange> = emptyList(),
        val renames: Map<String, String> = emptyMap(),
        val failure: HgResult? = null
    )

    private class RangeStatus(
        val range: DiffRange,
        val items: List<HgFileItem>,
        val failure: HgResult?
    )

    private fun collectRangeFiles(
        ranges: Map<String, FileRange>,
        cache: MutableMap<DiffRange, List<HgFileItem>>,
        trace: LoadTrace? = null,
        phaseName: String = "range files"
    ): RangeFiles {
        val groups = BranchRevisions.rangeGroups(ranges)
        val pending = groups.keys.filterNot { it in cache }
        val fetched = trace.timed(phaseName) {
            jobs.map(pending, { it.failure != null }) { range -> rangeStatus(range) }
        }
        for (status in fetched) {
            status.failure?.let { return RangeFiles(failure = it) }
            cache[status.range] = status.items
        }

        val files = ArrayList<HgFileItem>()
        val statsRanges = ArrayList<DiffRange>()
        val renames = HashMap<String, String>()
        for ((range, keys) in groups) {
            statsRanges.add(range)
            cache[range].orEmpty()
                .filter { inScope(it, keys) }
                .forEach { item ->
                    if (item.copiedFrom.isNotEmpty()) {
                        renames[HgPaths.key(item.path)] = HgPaths.key(item.copiedFrom)
                    }
                    files.add(item.copy(baseRev = range.base, headRev = range.head))
                }
        }
        return RangeFiles(files, statsRanges, renames)
    }

    private fun prefetchSingleRevisions(
        ranges: Map<String, FileRange>,
        revisions: List<HgRevision>,
        cache: MutableMap<DiffRange, List<HgFileItem>>,
        trace: LoadTrace? = null
    ) {
        val merges = revisions.filter { it.isMerge }.mapTo(HashSet()) { it.node }
        val singles = ChangesetFiles.singleRevisionRanges(ranges, merges)
        if (singles.size < ChangesetFiles.MIN_NODES) return

        val log = trace.timed("prefetch single revisions") {
            commands.run(
                "log", "-r", BranchRevisions.revset(singles.keys), "--template", ChangesetFiles.TEMPLATE
            )
        }
        if (!log.success) {
            warn("Changeset files are read one by one: ${HgFailure.message(log)}")
            return
        }

        val byNode = trace.timed("parse prefetched files") { ChangesetFiles.parse(log.stdout) }
        for ((node, range) in singles) {
            cache[range] = byNode[node] ?: continue
        }
    }

    private fun rangeStatus(range: DiffRange): RangeStatus {
        val statusRes = commands.run(
            "status", "--rev", range.base, "--rev", range.head,
            HgStatusParser.statusFlags(false), HgStatusParser.COPIES_FLAG
        )
        if (!statusRes.success) return RangeStatus(range, emptyList(), statusRes)
        return RangeStatus(range, HgStatusParser.foldRenames(HgStatusParser.parse(statusRes.stdout)), null)
    }

    private fun mergeTouches(node: String): List<String>? {
        val res = commands.run(
            "status", "--rev", "p1($node)", "--rev", node, HgStatusParser.statusFlags(false)
        )
        if (!res.success) return null
        return HgStatusParser.parse(res.stdout).map { it.path }
    }

    private class HeadQueries(
        val branch: String,
        val revisions: List<HgRevision>,
        val currentInfo: String,
        val baseInfo: String?
    )

    private fun headQueries(
        usesRevisions: Boolean,
        earlyTargetRev: String?,
        trace: LoadTrace? = null
    ): HeadQueries {
        val queries = ArrayList<() -> Any>()
        if (usesRevisions) {
            queries.add { currentBranch() }
            queries.add { branchRevisions() }
        }
        queries.add { revisionInfo(".") }
        if (earlyTargetRev != null) queries.add { revisionInfo(earlyTargetRev) }

        val answers = trace.timed("head queries") { jobs.map(queries) { it() } }
        var next = 0
        val branch = if (usesRevisions) answers[next++] as String else ""
        @Suppress("UNCHECKED_CAST")
        val revisions = if (usesRevisions) answers[next++] as List<HgRevision> else emptyList()
        val currentInfo = answers[next++] as String
        val baseInfo = if (earlyTargetRev != null) answers[next] as String else null
        return HeadQueries(branch, revisions, currentInfo, baseInfo)
    }

    private fun revisionInfo(rev: String): String =
        commands.run("log", "-r", rev, "--template", StatusTextFormatter.REVISION_TEMPLATE)
            .stdout.ifBlank { StatusTextFormatter.UNKNOWN_REVISION }

    private fun currentBranch(): String =
        commands.run("log", "-r", ".", "--template", "{branch}").stdout.trim()

    private fun branchRevisions(): List<HgRevision> {
        val r = commands.run(
            "log", "-r", BranchRevisions.OWN_REVS, "--template", BranchRevisions.TEMPLATE + "\\n"
        )
        return if (r.success) BranchRevisions.parse(r.stdout) else emptyList()
    }

    private fun inScope(item: HgFileItem, scope: Set<String>): Boolean =
        HgPaths.key(item.path) in scope || (item.copiedFrom.isNotEmpty() && HgPaths.key(item.copiedFrom) in scope)

    private fun branchOwnFiles(selectedNodes: List<String>): Set<String>? {
        val r = commands.run(
            "log", "-r", BranchRevisions.revset(selectedNodes), "--template", "{join(files, '\\n')}\\n"
        )
        if (!r.success) return null
        return BranchScope.ownFiles(r.stdout).ifEmpty { null }
    }
}

private fun <T> LoadTrace?.timed(name: String, work: () -> T): T =
    if (this == null) work() else phase(name, work)
