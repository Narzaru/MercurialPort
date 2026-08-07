package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem

/** Тексты строки состояния под тулбаром Hg Changes. */
object StatusTextFormatter {

    /** Шаблон `hg log`, из которого собирается описание ревизии. */
    const val REVISION_TEMPLATE = "{branch}|{node|short}|{desc|firstline}"

    /** Ответ `hg log`, если ревизию описать не удалось: тот же формат, три поля. */
    const val UNKNOWN_REVISION = "Unknown|?|?"

    /** `Branch: feature (a1b2c3) "Заголовок" vs Parent: default (…) "…"`. */
    fun branchInfo(mode: HgDisplayMode, currentInfo: String, baseInfo: String): String {
        if (mode == HgDisplayMode.UNCOMMITTED) return "Uncommitted in: ${revision(currentInfo)}"
        val relation = when (mode) {
            HgDisplayMode.BASE_BRANCH_HEAD -> " vs Head of: "
            HgDisplayMode.CUSTOM_BRANCH -> " vs Branch: "
            else -> " vs Parent: "
        }
        return "Branch: ${revision(currentInfo)}$relation${revision(baseInfo)}"
    }

    /** Сводка справа: сколько файлов, суммарные +/- и сколько просмотрено. */
    fun summary(files: List<HgFileItem>, reviewed: Int, statsPending: Boolean): String = when {
        files.isEmpty() -> " "
        statsPending -> "${files.size} files  ·  считаю ±…  ·  $reviewed/${files.size} reviewed"
        else -> {
            val added = files.sumOf { it.added }
            val removed = files.sumOf { it.removed }
            "${files.size} files  +$added  −$removed  ·  $reviewed/${files.size} reviewed"
        }
    }

    private fun revision(raw: String): String {
        val parts = raw.split('|', limit = 3)
        return if (parts.size >= 3) "${parts[0]} (${parts[1]}) \"${parts[2]}\"" else raw
    }
}
