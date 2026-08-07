package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.HgDiffStat

/**
 * Считает добавленные/удалённые строки по файлам из вывода `hg diff --git`. Даёт точные
 * числа (в отличие от `--stat`, где гистограмма масштабируется) и заодно показывает,
 * какие файлы изменились на самом деле.
 *
 * Разбор идёт прямо по байтам: дифф целой ветки — это мегабайты, и декодирование его
 * в строку со `split` подвешивало IDE. Декодируются только сами пути.
 */
object HgDiffStatParser {

    fun parse(bytes: ByteArray): Map<String, HgDiffStat> {
        val result = HashMap<String, HgDiffStat>()
        var path: String? = null
        var added = 0
        var removed = 0

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
                    }
                    // Заголовки ---/+++ не считаем, они есть у каждого файла.
                    startsWith(bytes, lineStart, PLUS_HEADER) || startsWith(bytes, lineStart, MINUS_HEADER) -> Unit
                    bytes[lineStart] == PLUS -> added++
                    bytes[lineStart] == MINUS -> removed++
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

    /** Из строки `diff --git a/path b/path` берёт путь после ` b/`. */
    private fun extractGitPath(bytes: ByteArray, start: Int, end: Int): String? {
        var i = start
        while (i + B_SLASH.size <= end) {
            if (startsWith(bytes, i, B_SLASH)) {
                val from = i + B_SLASH.size
                if (from >= end) return null
                return HgOutputDecoder.decode(bytes.copyOfRange(from, end)).trim().ifEmpty { null }
            }
            i++
        }
        return null
    }

    private const val NEW_LINE = '\n'.code.toByte()
    private const val CARRIAGE_RETURN = '\r'.code.toByte()
    private const val PLUS = '+'.code.toByte()
    private const val MINUS = '-'.code.toByte()
    private val DIFF_GIT_PREFIX = "diff --git ".toByteArray(Charsets.US_ASCII)
    private val PLUS_HEADER = "+++ ".toByteArray(Charsets.US_ASCII)
    private val MINUS_HEADER = "--- ".toByteArray(Charsets.US_ASCII)
    private val B_SLASH = " b/".toByteArray(Charsets.US_ASCII)
}
