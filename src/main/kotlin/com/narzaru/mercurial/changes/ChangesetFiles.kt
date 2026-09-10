package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgStatusParser
import com.narzaru.mercurial.model.HgFileItem

object ChangesetFiles {

    private const val NODE_MARKER = "@"

    private const val COPY_TARGET_MARKER = "* "

    private const val COPY_SOURCE_MARKER = "= "

    private const val ADDED_LINE_PREFIX = "A "

    private const val COPY_SOURCE_INDENT = "  "

    const val MIN_NODES = 2

    const val TEMPLATE =
        "$NODE_MARKER{node|short}\\n" +
            "{file_mods % \"M {file}\\n\"}" +
            "{file_adds % \"A {file}\\n\"}" +
            "{file_dels % \"R {file}\\n\"}" +
            "{file_copies % \"$COPY_TARGET_MARKER{name}\\n$COPY_SOURCE_MARKER{source}\\n\"}"

    fun singleRevisionRanges(ranges: Map<String, FileRange>, merges: Set<String>): Map<String, DiffRange> {
        val result = LinkedHashMap<String, DiffRange>()
        for (range in ranges.values) {
            if (range.first != range.last || range.first in merges) continue
            result[range.first] = DiffRange(range.base, range.last)
        }
        return result
    }

    fun parse(stdout: String): Map<String, List<HgFileItem>> {
        val result = LinkedHashMap<String, List<HgFileItem>>()
        var node: String? = null
        val statusLines = ArrayList<String>()
        val copies = LinkedHashMap<String, String>()
        var copyTarget: String? = null

        fun store() {
            val current = node ?: return
            result[current] = HgStatusParser.foldRenames(HgStatusParser.parse(statusText(statusLines, copies)))
        }

        for (line in stdout.split('\r', '\n')) {
            if (line.isEmpty()) continue
            when {
                line.startsWith(NODE_MARKER) -> {
                    store()
                    node = line.removePrefix(NODE_MARKER)
                    statusLines.clear()
                    copies.clear()
                    copyTarget = null
                }

                line.startsWith(COPY_TARGET_MARKER) -> copyTarget = line.removePrefix(COPY_TARGET_MARKER)

                line.startsWith(COPY_SOURCE_MARKER) -> {
                    copyTarget?.let { copies[it] = line.removePrefix(COPY_SOURCE_MARKER) }
                    copyTarget = null
                }

                else -> statusLines.add(line)
            }
        }
        store()
        return result
    }

    private fun statusText(lines: List<String>, copies: Map<String, String>): String {
        val text = StringBuilder()
        for (line in lines) {
            text.append(line).append('\n')
            if (!line.startsWith(ADDED_LINE_PREFIX)) continue
            val source = copies[line.removePrefix(ADDED_LINE_PREFIX)] ?: continue
            text.append(COPY_SOURCE_INDENT).append(source).append('\n')
        }
        return text.toString()
    }
}
