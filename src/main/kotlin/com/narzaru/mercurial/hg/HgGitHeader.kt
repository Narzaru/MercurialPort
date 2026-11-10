package com.narzaru.mercurial.hg

object HgGitHeader {

    const val PREFIX = "diff --git "

    private const val OLD_PREFIX = "a/"
    private const val NEW_SEPARATOR = " b/"

    private val SOURCE_PREFIXES = listOf("rename from ", "copy from ")
    private val TARGET_PREFIXES = listOf("rename to ", "copy to ")

    data class Names(val old: String, val new: String)

    fun names(header: String): Names? {
        val rest = header.removePrefix(PREFIX)
        symmetricName(rest)?.let { return Names(it, it) }
        val separator = rest.indexOf(NEW_SEPARATOR)
        if (separator < 0) return null
        return Names(rest.take(separator).removePrefix(OLD_PREFIX), rest.drop(separator + NEW_SEPARATOR.length))
    }

    fun source(line: String): String? = valueAfter(line, SOURCE_PREFIXES)

    fun target(line: String): String? = valueAfter(line, TARGET_PREFIXES)

    private fun symmetricName(rest: String): String? {
        if (!rest.startsWith(OLD_PREFIX)) return null
        val bothNamesLength = rest.length - OLD_PREFIX.length - NEW_SEPARATOR.length
        if (bothNamesLength <= 0 || bothNamesLength % 2 != 0) return null
        val half = bothNamesLength / 2
        val old = rest.substring(OLD_PREFIX.length, OLD_PREFIX.length + half)
        val tail = rest.substring(OLD_PREFIX.length + half)
        return old.takeIf { tail == NEW_SEPARATOR + it }
    }

    private fun valueAfter(line: String, prefixes: List<String>): String? {
        val prefix = prefixes.firstOrNull { line.startsWith(it) } ?: return null
        return line.substring(prefix.length).ifEmpty { null }
    }
}
