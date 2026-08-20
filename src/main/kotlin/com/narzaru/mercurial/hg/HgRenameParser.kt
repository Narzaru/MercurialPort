package com.narzaru.mercurial.hg

/**
 * Разбирает вывод `hg debugrename -r <rev> <файл>` — единственный дешёвый способ узнать
 * старое имя файла: команда читает метаданные filelog-а, а не сравнивает манифесты
 * (см. [HgLogParser] о цене `{file_copies}`).
 *
 * Формат ответа: `путь renamed from старый/путь:<хеш>` либо `путь not renamed`.
 */
object HgRenameParser {

    private const val MARKER = " renamed from "

    /** Старое имя файла или `null`, если в этой ревизии он не переименовывался. */
    fun sourceOf(output: String): String? {
        for (line in output.split('\r', '\n')) {
            val at = line.indexOf(MARKER)
            if (at < 0) continue
            val source = line.substring(at + MARKER.length)
            // Хеш filelog-а приписан через двоеточие; в самом пути двоеточий не бывает,
            // но на Windows возможен префикс диска — отрезаем последнее.
            val colon = source.lastIndexOf(':')
            val path = if (colon > 0) source.substring(0, colon) else source
            return path.trim().ifEmpty { null }?.let { HgPaths.normalize(it) }
        }
        return null
    }
}
