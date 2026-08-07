package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.HgFileItem

/**
 * Разбирает вывод `hg status`: строки вида `M path/to/file`, где первый символ —
 * статус, второй — пробел. Числа `+N −M` здесь не считаются: их отдельно догружает
 * [HgDiffStatParser], потому что дифф целой ветки идёт секундами.
 */
object HgStatusParser {

    fun parse(stdout: String): List<HgFileItem> =
        stdout.split('\r', '\n')
            // Короче трёх символов строка не может нести путь: `X ` плюс минимум один символ.
            .filter { it.length > 2 }
            .map { HgFileItem(status = it.substring(0, 1), path = it.substring(2).trim()) }

    /**
     * Флаги `hg status` для перечисления изменённых файлов.
     *
     * `r` — удалённые файлы: без него список ветки молча короче, чем на Upsource.
     * `d` — стёртые мимо hg (`!`); при сравнении ревизий таких не бывает, но в режиме
     * Uncommitted Only без него файл пропадает из списка вместо того, чтобы попасть в глаза.
     */
    fun statusFlags(includeUntracked: Boolean): String = if (includeUntracked) "-mardu" else "-mard"
}
