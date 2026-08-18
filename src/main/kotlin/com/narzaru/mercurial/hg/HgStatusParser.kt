package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.HgFileItem

/**
 * Разбирает вывод `hg status`: строки вида `M path/to/file`, где первый символ —
 * статус, второй — пробел. Числа `+N −M` здесь не считаются: их отдельно догружает
 * [HgDiffStatParser], потому что дифф целой ветки идёт секундами.
 *
 * С `--copies` после строки добавленного файла идёт строка источника с отступом в два
 * пробела — она относится к предыдущему файлу, а не описывает свой.
 */
object HgStatusParser {

    /** Статус переименованного файла: буквы у Mercurial для него нет, `R` занято удалением. */
    const val RENAMED_STATUS = "→"

    fun parse(stdout: String): List<HgFileItem> {
        val items = ArrayList<HgFileItem>()
        for (line in stdout.split('\r', '\n')) {
            // Короче трёх символов строка не может нести путь: `X ` плюс минимум один символ.
            if (line.length <= 2) continue
            if (line.startsWith("  ")) {
                // Источник копии/переименования — приписываем его предыдущему файлу.
                val last = items.lastOrNull() ?: continue
                items[items.size - 1] = last.copy(copiedFrom = line.trim())
                continue
            }
            items.add(HgFileItem(status = line.substring(0, 1), path = line.substring(2).trim()))
        }
        return items
    }

    /**
     * Сводит переименование в одну строку: `hg status` показывает его как удаление старого
     * пути плюс добавление нового, и без склейки старый файл выглядит просто удалённым, а
     * новый — целиком новым. Копия (источник на месте) остаётся добавлением, но помнит
     * источник — дифф от него показывает, что именно в копии поменяли.
     */
    fun foldRenames(items: List<HgFileItem>): List<HgFileItem> {
        val removed = items
            .filter { it.status == "R" || it.status == "!" }
            .map { HgPaths.key(it.path) }
            .toSet()
        val renameSources = HashSet<String>()

        val folded = items.map { item ->
            val source = item.copiedFrom
            if (item.status != "A" || source.isEmpty() || HgPaths.key(source) !in removed) {
                item
            } else {
                renameSources.add(HgPaths.key(source))
                item.copy(status = RENAMED_STATUS)
            }
        }
        return folded.filter { HgPaths.key(it.path) !in renameSources || it.status == RENAMED_STATUS }
    }

    /**
     * Флаги `hg status` для перечисления изменённых файлов.
     *
     * `r` — удалённые файлы: без него список ветки молча короче, чем на Upsource.
     * `d` — стёртые мимо hg (`!`); при сравнении ревизий таких не бывает, но в режиме
     * Uncommitted Only без него файл пропадает из списка вместо того, чтобы попасть в глаза.
     */
    fun statusFlags(includeUntracked: Boolean): String = if (includeUntracked) "-mardu" else "-mard"

    /** Просит `hg status` показывать источники переименований и копий. */
    const val COPIES_FLAG = "--copies"
}
