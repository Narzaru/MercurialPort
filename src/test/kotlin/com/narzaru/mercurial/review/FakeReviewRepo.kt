package com.narzaru.mercurial.review

import com.narzaru.mercurial.changes.HgCommands
import com.narzaru.mercurial.hg.HgResult

class FakeReviewRepo : HgCommands {

    private class Commit(
        val rev: Int,
        val node: String,
        val p1: String?,
        val p2: String?,
        val branch: String,
        val files: Map<String, String>,
        val renames: Map<String, String>
    )

    private val commits = LinkedHashMap<String, Commit>()

    val calls = ArrayList<List<String>>()

    fun commit(
        node: String,
        parent: String?,
        branch: String = "default",
        changes: Map<String, String?> = emptyMap(),
        renames: Map<String, String> = emptyMap()
    ): String {
        val files = LinkedHashMap(parent?.let { commits.getValue(it).files }.orEmpty())
        for ((to, from) in renames) files[to] = files.remove(from)!!
        for ((path, text) in changes) if (text == null) files.remove(path) else files[path] = text
        commits[node] = Commit(commits.size, node, parent, null, branch, files, renames)
        return node
    }

    fun merge(node: String, p1: String, p2: String, branch: String, changes: Map<String, String?>): String {
        val files = LinkedHashMap(commits.getValue(p1).files)
        for ((path, text) in changes) if (text == null) files.remove(path) else files[path] = text
        commits[node] = Commit(commits.size, node, p1, p2, branch, files, emptyMap())
        return node
    }

    @Synchronized
    override fun run(args: List<String>): HgResult {
        calls.add(args)
        return when (args.first()) {
            "log" -> HgResult(0, log(nodes(args[2])), "")
            "cat" -> cat(args[2], args[3].removePrefix("path:"))
            "status" -> HgResult(0, status(args[2], args[4]), "")
            else -> HgResult(255, "", "unexpected $args")
        }
    }

    fun count(command: String): Int = calls.count { it.first() == command }

    private fun cat(rev: String, path: String): HgResult {
        val text = commits[rev]?.files?.get(path) ?: return HgResult(1, "", "$path: no such file in rev $rev")
        return HgResult(0, text, "")
    }

    private fun nodes(revset: String): List<String> {
        val result = LinkedHashSet<String>()
        for (term in revset.split('+')) {
            val range = term.removePrefix("(").removeSuffix(")")
            if ("::" !in range) {
                result.add(range)
                continue
            }
            val from = range.substringBefore("::")
            val to = range.substringAfter("::")
            val below = ancestors(to)
            result.addAll(below.filter { from in ancestors(it) }.sortedBy { commits.getValue(it).rev })
        }
        return result.toList()
    }

    private fun ancestors(node: String): Set<String> {
        val seen = LinkedHashSet<String>()
        val pending = ArrayDeque(listOf(node))
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (!seen.add(current)) continue
            val commit = commits.getValue(current)
            listOfNotNull(commit.p1, commit.p2).forEach { pending.addLast(it) }
        }
        return seen
    }

    private fun status(from: String, to: String): String {
        val old = commits.getValue(from).files
        val new = commits.getValue(to).files
        val lines = ArrayList<String>()
        for ((path, text) in new) {
            if (path !in old) lines.add("A $path") else if (old[path] != text) lines.add("M $path")
        }
        for (path in old.keys) if (path !in new) lines.add("R $path")
        return lines.joinToString("\n") { it.replace('/', '\\') }
    }

    private fun log(nodes: List<String>): String = buildString {
        for (node in nodes) {
            val commit = commits.getValue(node)
            append("@").append(node).append('\n')
            append("^").append(commit.rev).append(' ').append(commit.p1 ?: NULL).append(' ').append(commit.p2 ?: NULL).append('\n')
            append("^").append(commit.branch).append('\n')
            append("^").append(commit.p2?.let { commits.getValue(it).branch }.orEmpty()).append('\n')
            if (commit.p2 != null) continue
            val old = commit.p1?.let { commits.getValue(it).files }.orEmpty()
            val new = commit.files
            for ((path, text) in new) if (path in old && old[path] != text) append("M ").append(path).append('\n')
            for (path in new.keys) if (path !in old) append("A ").append(path).append('\n')
            for (path in old.keys) if (path !in new) append("R ").append(path).append('\n')
            for ((to, from) in commit.renames) append("* ").append(to).append('\n').append("= ").append(from).append('\n')
        }
    }

    private companion object {
        const val NULL = "000000000000"
    }
}
