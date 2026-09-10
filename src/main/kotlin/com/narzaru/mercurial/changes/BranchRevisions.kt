package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths

data class HgRevision(
    val rev: String,
    val node: String,
    val isMerge: Boolean,
    val author: String,
    val date: String,
    val summary: String
)

object BranchRevisions {

    private const val FILES_MARKER = "@"

    const val TEMPLATE = "{rev}|{node|short}|{p2rev}|{author|person}|{date|isodate}|{desc|firstline}"

    private const val FIELD_COUNT = 6

    const val OWN_REVS = "branch(.) and ancestors(.)"

    const val NO_REVISION = "null"

    fun parse(stdout: String): List<HgRevision> {
        val items = ArrayList<HgRevision>()
        for (line in stdout.split('\r', '\n')) {
            if (line.isBlank()) continue
            val p = line.split('|', limit = FIELD_COUNT)
            if (p.size != FIELD_COUNT) continue
            items.add(
                HgRevision(
                    rev = p[0],
                    node = p[1],
                    isMerge = p[2].trim().let { it.isNotEmpty() && it != "-1" },
                    author = p[3],
                    date = p[4],
                    summary = p[5]
                )
            )
        }
        return items
    }

    const val FILES_TEMPLATE = "$FILES_MARKER{node|short}\\n{join(files, '\\n')}\\n"

    fun parseFileTouches(stdout: String): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (raw in stdout.split('\r', '\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith(FILES_MARKER)) {
                current = result.getOrPut(line.removePrefix(FILES_MARKER)) { ArrayList() }
            } else {
                current?.add(line)
            }
        }
        return result
    }

    fun fileRanges(
        nodes: List<String>,
        touches: Map<String, List<String>>,
        listed: Set<String>? = null
    ): Map<String, FileRange> {
        val ranges = LinkedHashMap<String, FileRange>()
        for (node in nodes) {
            for (path in touches[node].orEmpty()) {
                val key = HgPaths.key(path)
                if (listed != null && key !in listed) continue
                val existing = ranges[key]
                ranges[key] = if (existing == null) FileRange(node, node, path, listOf(node))
                else existing.copy(last = node, revs = existing.revs + node)
            }
        }
        return ranges
    }

    fun rangeGroups(ranges: Map<String, FileRange>): Map<DiffRange, Set<String>> {
        val groups = LinkedHashMap<DiffRange, MutableSet<String>>()
        for (range in ranges.values) {
            groups.getOrPut(DiffRange(range.base, range.last)) { LinkedHashSet() }
                .add(HgPaths.key(range.path))
        }
        return groups
    }

    fun mergeRenames(
        ranges: Map<String, FileRange>,
        renames: Map<String, String>,
        order: List<String>
    ): Map<String, FileRange>? {
        val rank = order.withIndex().associate { (i, node) -> node to i }
        val result = LinkedHashMap(ranges)
        var changed = false
        for ((newKey, oldKey) in renames) {
            val new = result[newKey] ?: continue
            val old = result[oldKey] ?: continue
            val revs = (new.revs + old.revs).distinct().sortedBy { rank[it] ?: 0 }
            if (revs == new.revs && revs == old.revs) continue
            result[newKey] = FileRange(revs.first(), revs.last(), new.path, revs)
            result[oldKey] = FileRange(revs.first(), revs.last(), old.path, revs)
            changed = true
        }
        return if (changed) result else null
    }

    fun revset(nodes: Collection<String>): String = nodes.joinToString("+")

    fun baseRevset(nodes: Collection<String>): String = "p1(min(${revset(nodes)}))"

    fun mergeBaseRevset(parentBranch: String): String =
        "max(ancestors(.) and branch('${parentBranch.replace("\\", "\\\\").replace("'", "\\'")}'))"

    fun chipText(revision: HgRevision, limit: Int = SUMMARY_LIMIT): String {
        val prefix = if (revision.isMerge) "merge " else ""
        val summary = if (revision.summary.length <= limit) revision.summary
        else revision.summary.take(limit - 1).trimEnd() + "…"
        return "${revision.node}  $prefix$summary"
    }

    fun tooltip(revision: HgRevision): String =
        "${revision.rev}:${revision.node} · ${revision.author} · ${revision.date}\n${revision.summary}"

    fun header(revisions: List<HgRevision>, selection: RevisionSelection): String {
        if (revisions.isEmpty()) return "Revisions: none"
        val selected = revisions.count { selection.isSelected(it) }
        val custom = if (selection.isDefaultFor(revisions)) "" else " (custom)"
        return "Revisions: $selected of ${revisions.size}$custom"
    }

    private const val SUMMARY_LIMIT = 60
}

data class FileRange(
    val first: String,
    val last: String,
    val path: String,

    val revs: List<String> = listOf(first)
) {

    val base: String get() = "p1($first)"
}

class RevisionSelection private constructor(private val overrides: Map<String, Boolean>) {

    fun isSelected(revision: HgRevision): Boolean = overrides[revision.node] ?: !revision.isMerge

    fun with(revision: HgRevision, selected: Boolean): RevisionSelection {
        val next = HashMap(overrides)
        if (selected == !revision.isMerge) next.remove(revision.node) else next[revision.node] = selected
        return RevisionSelection(next)
    }

    fun isDefaultFor(revisions: List<HgRevision>): Boolean =
        revisions.none { overrides.containsKey(it.node) }

    fun selectedNodes(revisions: List<HgRevision>): List<String> =
        revisions.filter { isSelected(it) }.map { it.node }

    fun toStorage(): List<String> = overrides.map { (node, on) -> "$node=${if (on) "1" else "0"}" }.sorted()

    companion object {
        val DEFAULT = RevisionSelection(emptyMap())

        fun fromStorage(values: List<String>): RevisionSelection {
            val map = HashMap<String, Boolean>()
            for (value in values) {
                val node = value.substringBefore('=', "").trim()
                if (node.isEmpty() || !value.contains('=')) continue
                map[node] = value.substringAfter('=').trim() == "1"
            }
            return RevisionSelection(map)
        }
    }
}
