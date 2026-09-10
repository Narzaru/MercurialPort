package com.narzaru.mercurial.hg

data class PatchHunk(
    val oldStart: Int,
    val newStart: Int,
    val oldLines: List<String>,
    val newLines: List<String>,
    val leading: Int = 0,
    val trailing: Int = 0
)

object HgPatchApplier {

    fun parseHunks(patch: String, paths: Collection<String> = emptyList()): List<PatchHunk> {
        val sections = splitFiles(patch)
        val wanted = paths.mapTo(HashSet()) { HgPaths.key(it) }
        val section = when {
            sections.isEmpty() -> return emptyList()
            wanted.isEmpty() -> sections.first()
            sections.size == 1 && sections.first().names.isEmpty() -> sections.first()
            else -> sections.firstOrNull { it.names.any { name -> name in wanted } } ?: return emptyList()
        }
        return parseSection(section.lines)
    }

    private class PatchFile(val names: Set<String>, val lines: List<String>)

    private fun splitFiles(patch: String): List<PatchFile> {
        val files = ArrayList<PatchFile>()
        var names = emptySet<String>()
        var lines = ArrayList<String>()

        fun flush() {
            if (lines.isNotEmpty()) files.add(PatchFile(names, lines))
            names = emptySet()
            lines = ArrayList()
        }

        for (raw in patch.split('\n')) {
            val text = raw.removeSuffix("\r")
            if (text.startsWith(FILE_HEADER)) {
                flush()
                names = headerNames(text)
            } else {
                lines.add(text)
            }
        }
        flush()
        return files
    }

    private fun headerNames(header: String): Set<String> {
        val rest = header.removePrefix(FILE_HEADER)
        val b = rest.indexOf(" b/")
        if (b < 0) return emptySet()
        val old = rest.take(b).removePrefix("a/")
        val new = rest.drop(b + " b/".length)
        return setOf(HgPaths.key(old), HgPaths.key(new))
    }

    private fun parseSection(lines: List<String>): List<PatchHunk> {
        val hunks = ArrayList<PatchHunk>()
        var oldStart = 0
        var newStart = 0
        var old: MutableList<String>? = null
        var new: MutableList<String>? = null
        var leading = 0
        var trailing = 0
        var changeSeen = false

        fun flush() {
            val o = old ?: return
            hunks.add(PatchHunk(oldStart, newStart, o, new.orEmpty(), leading, trailing))
            old = null
            new = null
            leading = 0
            trailing = 0
            changeSeen = false
        }

        for (text in lines) {
            when {
                text.startsWith("@@") -> {
                    flush()
                    oldStart = parseStart(text, '-')
                    newStart = parseStart(text, '+')
                    old = ArrayList()
                    new = ArrayList()
                }
                old == null -> Unit
                text.startsWith("+++") || text.startsWith("---") -> Unit
                text.startsWith("\\") -> Unit
                text.startsWith("+") -> {
                    new?.add(text.substring(1)); changeSeen = true; trailing = 0
                }
                text.startsWith("-") -> {
                    old?.add(text.substring(1)); changeSeen = true; trailing = 0
                }
                text.startsWith(" ") -> {
                    old?.add(text.substring(1))
                    new?.add(text.substring(1))
                    if (changeSeen) trailing++ else leading++
                }
                else -> flush()
            }
        }
        flush()
        return hunks
    }

    data class Applied(val lines: List<String>, val skipped: Int)

    fun applyReverse(lines: List<String>, hunks: List<PatchHunk>): Applied {
        val result = ArrayList(lines)
        var skipped = 0
        var searchUpTo = result.size
        for (hunk in hunks.asReversed()) {
            val flipped = PatchHunk(
                hunk.newStart, hunk.oldStart, hunk.newLines, hunk.oldLines, hunk.leading, hunk.trailing
            )
            val placed = place(result, flipped, flipped.oldStart, 0, searchUpTo) { at, _ ->
                searchUpTo = at
            }
            if (!placed) skipped++
        }
        return Applied(result, skipped)
    }

    private inline fun place(
        result: ArrayList<String>,
        hunk: PatchHunk,
        hint: Int,
        from: Int,
        until: Int,
        onPlaced: (at: Int, newSize: Int) -> Unit
    ): Boolean {
        val front = minOf(MAX_FUZZ, hunk.leading)
        val back = minOf(MAX_FUZZ, hunk.trailing)
        val cuts = (0..front).flatMap { f -> (0..back).map { b -> f to b } }.sortedBy { it.first + it.second }
        for ((cutFront, cutBack) in cuts) {
            val old = hunk.oldLines.drop(cutFront).dropLast(cutBack)
            val new = hunk.newLines.drop(cutFront).dropLast(cutBack)
            if (old.isEmpty()) continue
            val at = find(result, old, (hint - 1 + cutFront).coerceAtLeast(0), from, until) ?: continue
            result.subList(at, at + old.size).clear()
            result.addAll(at, strip(new))
            onPlaced(at, new.size)
            return true
        }
        return false
    }

    private fun find(lines: List<String>, what: List<String>, hint: Int, from: Int, until: Int): Int? {
        val bound = minOf(until, lines.size)
        if (what.isEmpty()) return hint.coerceIn(from, maxOf(from, bound))
        val last = bound - what.size
        if (last < from) return null
        val start = hint.coerceIn(from, last)
        var offset = 0
        while (start - offset >= from || start + offset <= last) {
            val forward = start + offset
            if (forward <= last && matches(lines, forward, what)) return forward
            val back = start - offset
            if (back >= from && matches(lines, back, what)) return back
            offset++
        }
        return null
    }

    private fun matches(lines: List<String>, at: Int, what: List<String>): Boolean {
        for (i in what.indices) {
            if (normalize(lines[at + i]) != normalize(what[i])) return false
        }
        return true
    }

    private fun normalize(line: String): String = line.removeSuffix("\r").removePrefix(BOM)

    private fun strip(lines: List<String>): List<String> = lines.map { it.removePrefix(BOM) }

    private const val BOM = "﻿"

    private const val FILE_HEADER = "diff --git "

    private const val MAX_FUZZ = 3

    private fun parseStart(header: String, side: Char): Int {
        val at = header.indexOf(side)
        if (at < 0) return 0
        val digits = header.drop(at + 1).takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }
}
