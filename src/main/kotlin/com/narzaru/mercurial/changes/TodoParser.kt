package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem

/**
 * Ищет TODO-комментарии в тексте файла:
 * учитывает как строчные (//), так и блочные (/* */) комментарии.
 */
object TodoParser {

    private const val TODO = "todo"
    private const val LINE_COMMENT = "//"
    private const val BLOCK_OPEN = "/*"
    private const val BLOCK_CLOSE = "*/"

    /** Записан escape-последовательностью намеренно: голый символ в коде неотличим от пробела. */
    private const val NUL = '\u0000'

    fun parse(source: HgFileItem, text: String): List<HgFileItem> {
        val result = ArrayList<HgFileItem>()
        // Нулевой байт — признак бинарного файла: комментариев в нём нет, а «строк»
        // из мегабайтного .dll получатся десятки тысяч.
        if (text.contains(NUL)) return result
        // Разбирать построчно есть смысл, только если слово вообще встречается в файле:
        // TODO-режим перечитывает все изменённые файлы по таймеру.
        if (!text.contains(TODO, ignoreCase = true)) return result

        val lines = text.split("\r\n", "\n", "\r")
        var inBlockComment = false

        for (index in lines.indices) {
            val line = lines[index]
            val (found, blockState) = containsTodoInComment(line, inBlockComment)
            inBlockComment = blockState
            if (!found) continue

            result.add(
                HgFileItem(
                    status = source.status,
                    path = source.path,
                    isUnchanged = source.isUnchanged,
                    todoText = formatTodoText(line),
                    lineNumber = index + 1
                )
            )
        }
        return result
    }

    /** @return (найден ли TODO в комментарии на этой строке, актуальное состояние inBlockComment). */
    private fun containsTodoInComment(line: String, blockCommentState: Boolean): Pair<Boolean, Boolean> {
        var inBlockComment = blockCommentState
        var position = 0
        while (position < line.length) {
            if (inBlockComment) {
                val blockEnd = line.indexOf(BLOCK_CLOSE, position)
                val commentEnd = if (blockEnd < 0) line.length else blockEnd
                val todo = line.indexOf(TODO, position, ignoreCase = true)
                if (todo in position until commentEnd) {
                    if (blockEnd >= 0) inBlockComment = false
                    return true to inBlockComment
                }
                if (blockEnd < 0) return false to inBlockComment
                inBlockComment = false
                position = blockEnd + BLOCK_CLOSE.length
                continue
            }

            val lineComment = line.indexOf(LINE_COMMENT, position)
            val blockComment = line.indexOf(BLOCK_OPEN, position)

            // Что началось раньше, то и определяет тип комментария: `/* … // … */`
            // и `// … /* …` разбираются по-разному.
            if (lineComment >= 0 && (blockComment < 0 || lineComment < blockComment)) {
                val found = line.indexOf(TODO, lineComment + LINE_COMMENT.length, ignoreCase = true) >= 0
                return found to inBlockComment
            }

            if (blockComment < 0) return false to inBlockComment
            inBlockComment = true
            position = blockComment + BLOCK_OPEN.length
        }
        return false to inBlockComment
    }

    /** Оставляет от строки сам текст задачи: `// TODO: убрать хак` → `убрать хак`. */
    private fun formatTodoText(line: String): String {
        var original = line.trim()
        val todoIndex = original.indexOf(TODO, ignoreCase = true)
        if (todoIndex >= 0) {
            val lineComment = original.lastIndexOf(LINE_COMMENT, todoIndex)
            val blockComment = original.lastIndexOf(BLOCK_OPEN, todoIndex)
            val commentStart = maxOf(lineComment, blockComment)
            if (commentStart > 0) original = original.substring(commentStart).trim()
        }

        // `*` и `/` — продолжение блочного комментария в несколько строк.
        for (prefix in arrayOf(LINE_COMMENT, BLOCK_OPEN, "*", "/")) {
            if (!original.startsWith(prefix)) continue
            var remainder = original.substring(prefix.length).trimStart()
            if (!remainder.startsWith(TODO, ignoreCase = true)) return original

            remainder = remainder.substring(TODO.length).trimStart(' ', '\t', ':', '-')
            if (remainder.endsWith(BLOCK_CLOSE)) {
                remainder = remainder.dropLast(BLOCK_CLOSE.length).trimEnd()
            }
            return if (remainder.isEmpty()) original else remainder
        }
        return original
    }
}
