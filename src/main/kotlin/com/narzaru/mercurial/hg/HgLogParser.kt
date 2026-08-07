package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.HgHistoryItem

/**
 * Разбирает вывод `hg log -f` для окна истории файла.
 *
 * Копии печатаются своим разделителем: у `{file_copies}` записи «new (old)» разделены
 * пробелом и неразличимы, если в пути есть пробел.
 */
object HgLogParser {

    /** Шаблон `hg log`, который умеет разобрать [parse]. Поля разделены `|`. */
    const val TEMPLATE = "{rev}|{node|short}|{author|person}|{date|isodate}|{p1rev}|" +
        "{file_copies % '{source}=>{name};'}|{desc|firstline}"

    private const val FIELD_COUNT = 7

    /**
     * @param stdout вывод `hg log -f <файл> --template "$TEMPLATE\n"`
     * @param relativePath путь файла на момент запроса — от него «отматываются» переименования
     */
    fun parse(stdout: String, relativePath: String): List<HgHistoryItem> {
        val items = ArrayList<HgHistoryItem>()
        // Список идёт от новых к старым, поэтому путь «отматываем» назад:
        // переименование в ревизии R означает, что у всех предков имя было старым.
        var pathAtRev = HgPaths.normalize(relativePath)

        for (line in stdout.split('\r', '\n')) {
            if (line.isBlank()) continue
            val p = line.split('|', limit = FIELD_COUNT)
            if (p.size != FIELD_COUNT) continue
            val parentPath = copySourceOf(p[5], pathAtRev) ?: pathAtRev
            items.add(
                HgHistoryItem(
                    revision = p[0], node = p[1], author = p[2], date = p[3], message = p[6],
                    path = pathAtRev, parentRev = p[4], parentPath = parentPath
                )
            )
            pathAtRev = parentPath
        }
        return items
    }

    /**
     * Ищет в списке копий ревизии (`source=>name;`) запись, создавшую [path],
     * и возвращает исходное имя — путь этого же файла у родительской ревизии.
     */
    fun copySourceOf(copies: String, path: String): String? {
        for (entry in copies.split(';')) {
            val sep = entry.indexOf("=>")
            if (sep < 0) continue
            val source = entry.substring(0, sep)
            val name = entry.substring(sep + 2)
            if (name.equals(path, ignoreCase = true) && source.isNotBlank()) return source
        }
        return null
    }
}
