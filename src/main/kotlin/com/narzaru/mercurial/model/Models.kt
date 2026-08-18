package com.narzaru.mercurial.model

import java.io.File

/**
 * Режимы сравнения в главном окне. Название говорит, с чем сравнивают, [hint] — зачем это
 * нужно и чем режим отличается от соседнего: по одному заголовку разница между двумя
 * режимами ветки (неподвижная база против вершины родителя) не читалась.
 */
enum class HgDisplayMode(val title: String, val hint: String) {
    UNCOMMITTED(
        "Uncommitted changes",
        "Незакоммиченные правки рабочего каталога."
    ),
    BRANCH(
        "Branch changes (since branch point)",
        "Что сделано в ветке: сравнение с ревизией, от которой её ответвили. База неподвижна, " +
            "что бы ни коммитили в родительскую ветку, — это режим ревью, тот же дифф показывает " +
            "Upsource. В списке только файлы, которых касались ревизии самой ветки."
    ),
    BASE_BRANCH_HEAD(
        "Branch changes vs parent HEAD",
        "Те же файлы ветки, но сравнение с текущей вершиной родительской ветки. База уезжает " +
            "вперёд с каждым коммитом коллег: видно, что ветка принесёт в родителя и где разошлась " +
            "с чужими правками."
    ),
    CUSTOM_BRANCH(
        "vs branch",
        "Сравнение с указанной веткой целиком, без ограничения по файлам ветки."
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
