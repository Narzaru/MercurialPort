package com.narzaru.mercurial.model

import java.io.File

/**
 * Comparison modes of the main tool window. The title says what is compared against, [hint]
 * says why the mode exists and how it differs from its neighbour: the titles alone did not
 * convey the difference between the two branch modes (a fixed base against the parent's head).
 */
enum class HgDisplayMode(val title: String, val hint: String) {
    UNCOMMITTED(
        "Uncommitted",
        "Uncommitted edits in the working directory."
    ),
    BRANCH(
        "Base",
        "What the branch itself has done: compared against the revision it was branched off. The " +
            "base does not move, so commits other people make in the parent branch do not affect the " +
            "list — this is the review mode, the same diff Upsource shows. Only files touched by the " +
            "branch's own revisions are listed."
    ),
    CUSTOM_BRANCH(
        "VS branch",
        "Compared against the branch named in the field on the right, as a whole: the file list is " +
            "not restricted to the branch's own files."
    )
}

/** Режим списка: файлы или TODO. */
enum class HgListMode { FILES, TODO }

/**
 * Элемент списка изменённых файлов (или TODO-строки).
 *
 * [copiedFrom] — путь источника для переименования или копии (`hg status --copies`).
 * Базовую сторону диффа для таких файлов надо читать по нему: под своим именем в базовой
 * ревизии файла ещё нет, и без источника переименование выглядит как файл целиком новый.
 */
data class HgFileItem(
    var status: String,
    val path: String,
    val isUnchanged: Boolean = false,
    val todoText: String? = null,
    val lineNumber: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val copiedFrom: String = ""
) {
    /** Путь, под которым файл лежит в базовой ревизии. */
    val basePath: String get() = copiedFrom.ifEmpty { path }

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
