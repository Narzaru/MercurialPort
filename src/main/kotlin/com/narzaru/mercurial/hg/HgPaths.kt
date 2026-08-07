package com.narzaru.mercurial.hg

import java.io.File

/**
 * Работа с путями репозитория. Mercurial везде отдаёт и принимает пути через `/`,
 * а на Windows файловая система — через `\`, поэтому сравнивать и класть в ключи
 * можно только нормализованную форму.
 */
object HgPaths {

    /** Путь в форме Mercurial: разделители `/`. */
    fun normalize(path: String): String = path.replace('\\', '/')

    /** Нормализованный путь в нижнем регистре — ключ для сравнения путей без учёта регистра. */
    fun key(path: String): String = normalize(path).lowercase()

    /**
     * Путь [file] относительно [root]. Если файл лежит вне корня, вернём одно имя:
     * относительный путь для него всё равно не построить, а звать `hg` с абсолютным
     * путём чужого дерева бессмысленно.
     */
    fun relativize(file: File, root: File): String = relativize(file.absolutePath, root.absolutePath)
        ?: file.name

    /**
     * То же для строк. `null`, если [path] не лежит внутри [root] — в отличие от
     * [relativize] с [File] здесь вызывающая сторона сама решает, чем это заменить.
     */
    fun relativize(path: String, root: String): String? {
        // Регистр не учитываем: на Windows один и тот же каталог приходит и как
        // `D:\repo`, и как `d:\repo` — от диалогов платформы и от самого hg.
        if (!path.startsWith(root, ignoreCase = true)) return null
        return path.substring(root.length).trimStart('\\', '/')
    }
}
