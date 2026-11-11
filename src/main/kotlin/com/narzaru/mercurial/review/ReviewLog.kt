package com.narzaru.mercurial.review

import com.narzaru.mercurial.changes.ChangesetFiles
import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgFileStatus

data class ReviewRevision(
    val rev: Int,
    val node: String,
    val p1: String?,
    val p2: String?,
    val branch: String,
    val p2Branch: String = "",
    val changes: List<HgFileItem> = emptyList()
) {

    val isMerge: Boolean get() = p2 != null

    val parents: List<String> get() = listOfNotNull(p1, p2)

    fun touchedPaths(): Set<String> {
        val paths = LinkedHashSet<String>()
        for (item in changes) {
            paths.add(item.path)
            if (item.status == HgFileStatus.RENAMED) paths.add(item.copiedFrom)
        }
        return paths
    }
}

object ReviewLog {

    private const val HEADER_MARKER = "^"

    private const val NULL_NODE = "000000000000"

    const val TEMPLATE =
        "${ChangesetFiles.NODE_MARKER}{node|short}\\n" +
            "$HEADER_MARKER{rev} {p1node|short} {p2node|short}\\n" +
            "$HEADER_MARKER{branch}\\n" +
            "$HEADER_MARKER{revset('p2(%d)', rev) % '{branch}'}\\n" +
            ChangesetFiles.FILES_TEMPLATE

    fun args(revset: String): List<String> = listOf("log", "-r", revset, "--template", TEMPLATE)

    fun parse(stdout: String): List<ReviewRevision> {
        val headers = LinkedHashMap<String, MutableList<String>>()
        val fileLines = StringBuilder()
        var node: String? = null
        for (line in stdout.split('\n')) {
            when {
                line.startsWith(ChangesetFiles.NODE_MARKER) -> {
                    node = line.removePrefix(ChangesetFiles.NODE_MARKER).trim()
                    headers[node] = ArrayList()
                    fileLines.append(line).append('\n')
                }

                line.startsWith(HEADER_MARKER) -> node?.let { headers[it]?.add(line.removePrefix(HEADER_MARKER)) }

                else -> fileLines.append(line).append('\n')
            }
        }
        val files = ChangesetFiles.parse(fileLines.toString())
        return headers.mapNotNull { (node, header) -> revision(node, header, files[node].orEmpty()) }
    }

    private fun revision(node: String, header: List<String>, changes: List<HgFileItem>): ReviewRevision? {
        val ids = header.getOrNull(0)?.trim()?.split(' ') ?: return null
        if (ids.size != PARENT_FIELDS) return null
        val rev = ids[0].toIntOrNull() ?: return null
        val p2 = parent(ids[2])
        return ReviewRevision(
            rev = rev,
            node = node,
            p1 = parent(ids[1]),
            p2 = p2,
            branch = header.getOrNull(1).orEmpty(),
            p2Branch = header.getOrNull(2).orEmpty(),
            changes = if (p2 == null) changes else emptyList()
        )
    }

    private fun parent(node: String): String? = node.takeIf { it.isNotEmpty() && it != NULL_NODE }

    private const val PARENT_FIELDS = 3
}
