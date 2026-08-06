package com.narzaru.mercurial.model

import java.io.File

/** Режимы сравнения в главном окне. */
enum class HgDisplayMode(val title: String) {
    UNCOMMITTED("Uncommitted Only"),
    BRANCH("Entire Branch (vs Parent)"),
    BASE_BRANCH_HEAD("Vs Parent Branch HEAD"),
    CUSTOM_BRANCH("vs")
}

/** Режим списка: файлы или TODO. */
enum class HgListMode { FILES, TODO }

/** Элемент списка изменённых файлов (или TODO-строки). */
data class HgFileItem(
    var status: String,
    val path: String,
    val isUnchanged: Boolean = false,
    val todoText: String? = null,
    val lineNumber: Int = 0,
    val added: Int = 0,
    val removed: Int = 0
) {
    val isTodoItem: Boolean get() = lineNumber > 0

    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')

    val displayPath: String
        get() = if (isTodoItem) "${File(path).name}:$lineNumber" else path
}

/** Количество добавленных/удалённых строк по файлам (разбор `hg diff --git`). */
data class HgDiffStat(val added: Int, val removed: Int)

/**
 * Одна ревизия в окне истории файла.
 *
 * [path] и [parentPath] хранятся отдельно, потому что `hg log -f` следует за
 * переименованиями: у одной и той же строки истории путь до и после ревизии
 * может отличаться, и `hg cat` для родителя нужно звать со старым именем.
 * [parentRev] — первый родитель (`{p1rev}`), «-1» для корневой ревизии.
 */
data class HgHistoryItem(
    val revision: String,
    val node: String,
    val author: String,
    val date: String,
    val message: String,
    val path: String = "",
    val parentRev: String = "",
    val parentPath: String = ""
)
