package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.HgDiffStat

object HgDiffStatParser {

    fun parse(bytes: ByteArray): Map<String, HgDiffStat> {
        val result = HashMap<String, HgDiffStat>()
        var path: String? = null
        var added = 0
        var removed = 0
        var inHeader = false
        var inBinaryPatch = false

        fun flush() {
            path?.let { result[it] = HgDiffStat(added, removed) }
            added = 0
            removed = 0
        }

        var lineStart = 0
        while (lineStart < bytes.size) {
            var lineEnd = lineStart
            while (lineEnd < bytes.size && bytes[lineEnd] != NEW_LINE) lineEnd++
            var contentEnd = lineEnd
            if (contentEnd > lineStart && bytes[contentEnd - 1] == CARRIAGE_RETURN) contentEnd--

            if (contentEnd > lineStart) {
                when {
                    startsWith(bytes, lineStart, DIFF_GIT_PREFIX) -> {
                        flush()
                        path = extractGitPath(bytes, lineStart, contentEnd)
                        inHeader = true
                        inBinaryPatch = false
                    }
                    inBinaryPatch -> Unit
                    startsWith(bytes, lineStart, BINARY_PATCH) -> inBinaryPatch = true
                    startsWith(bytes, lineStart, RENAME_TO) ->
                        path = decode(bytes, lineStart + RENAME_TO.size, contentEnd) ?: path
                    startsWith(bytes, lineStart, COPY_TO) ->
                        path = decode(bytes, lineStart + COPY_TO.size, contentEnd) ?: path
                    inHeader && isHeaderLine(bytes, lineStart) -> Unit
                    else -> {
                        inHeader = false
                        when (bytes[lineStart]) {
                            PLUS -> added++
                            MINUS -> removed++
                        }
                    }
                }
            }
            lineStart = lineEnd + 1
        }
        flush()
        return result
    }

    private fun startsWith(bytes: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (offset + prefix.size > bytes.size) return false
        for (i in prefix.indices) {
            if (bytes[offset + i] != prefix[i]) return false
        }
        return true
    }

    private fun isHeaderLine(bytes: ByteArray, offset: Int): Boolean =
        HEADER_PREFIXES.any { startsWith(bytes, offset, it) }

    private fun extractGitPath(bytes: ByteArray, start: Int, end: Int): String? {
        var i = start
        while (i + B_SLASH.size <= end) {
            if (startsWith(bytes, i, B_SLASH)) return decode(bytes, i + B_SLASH.size, end)
            i++
        }
        return null
    }

    private fun decode(bytes: ByteArray, from: Int, end: Int): String? {
        if (from >= end) return null
        return HgOutputDecoder.decode(bytes.copyOfRange(from, end)).trim().ifEmpty { null }
    }

    private const val NEW_LINE = '\n'.code.toByte()
    private const val CARRIAGE_RETURN = '\r'.code.toByte()
    private const val PLUS = '+'.code.toByte()
    private const val MINUS = '-'.code.toByte()
    private val DIFF_GIT_PREFIX = "diff --git ".toByteArray(Charsets.US_ASCII)
    private val B_SLASH = " b/".toByteArray(Charsets.US_ASCII)
    private val RENAME_TO = "rename to ".toByteArray(Charsets.US_ASCII)
    private val COPY_TO = "copy to ".toByteArray(Charsets.US_ASCII)
    private val BINARY_PATCH = "GIT binary patch".toByteArray(Charsets.US_ASCII)
    private val HEADER_PREFIXES = listOf(
        "--- ",
        "+++ ",
        "index ",
        "old mode ",
        "new mode ",
        "new file mode ",
        "deleted file mode ",
        "similarity index ",
        "dissimilarity index ",
        "rename from ",
        "copy from ",
        "Binary file"
    ).map { it.toByteArray(Charsets.US_ASCII) }
}
