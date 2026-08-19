package com.narzaru.mercurial.history

/**
 * Дата ревизии в списке истории. `hg log` отдаёт её как `{date|isodate}` —
 * «2026-01-01 12:00 +0300».
 *
 * В колонке показываем только день: время и часовой пояс занимают половину её ширины,
 * а различают ревизии редко — полное значение остаётся в тултипе.
 */
object HistoryDateText {

    /** День без времени и часового пояса. */
    fun day(date: String): String = date.trim().substringBefore(' ')

    /** Полное значение для тултипа; пустое — тултипа нет. */
    fun full(date: String): String? = date.trim().ifEmpty { null }
}
