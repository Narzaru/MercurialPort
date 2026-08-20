package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.HgHistoryItem

/**
 * Разбирает вывод `hg log -f` для окна истории файла.
 *
 * Переименований в шаблоне **нет** намеренно. `{file_copies}` заставляет Mercurial искать копии
 * в каждой ревизии — это сравнение манифестов, ~180 мс на ревизию: на файле с историей в 1235
 * ревизий (`ProjectStudio_Next`) шаблон с копиями считался 221 с против 1.5 с без них. Старое имя
 * нужно куда реже, чем список ревизий, поэтому оно выясняется по требованию — одним
 * `hg debugrename` на найденное переименование (см. [HgRenameParser]).
 */
object HgLogParser {

    /** Шаблон `hg log`, который умеет разобрать [parse]. Поля разделены `|`. */
    const val TEMPLATE = "{rev}|{node|short}|{author|person}|{date|isodate}|{p1rev}|{desc|firstline}"

    private const val FIELD_COUNT = 6

    /**
     * @param stdout вывод `hg log -f <файл> --template "$TEMPLATE\n"`
     * @param relativePath путь файла на момент запроса
     */
    fun parse(stdout: String, relativePath: String): List<HgHistoryItem> {
        val items = ArrayList<HgHistoryItem>()
        val path = HgPaths.normalize(relativePath)

        for (line in stdout.split('\r', '\n')) {
            if (line.isBlank()) continue
            val p = line.split('|', limit = FIELD_COUNT)
            if (p.size != FIELD_COUNT) continue
            items.add(
                HgHistoryItem(
                    revision = p[0], node = p[1], author = p[2], date = p[3], message = p[5],
                    path = path, parentRev = p[4], parentPath = path
                )
            )
        }
        return items
    }
}
