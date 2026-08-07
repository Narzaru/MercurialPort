package com.narzaru.mercurial.changes

/** Результат подгонки: сам текст и признак, что его пришлось обрезать (тогда нужен тултип). */
data class FittedText(val text: String, val truncated: Boolean)

/**
 * Укорачивает текст под доступную ширину, обрезая хвост (`Cadwise.ObjectLib.Comm…`).
 *
 * Ширины считает переданная функция, а не FontMetrics напрямую: так подгонку можно
 * проверить тестом, да и звать что-либо у дерева из рендерера всё равно нельзя.
 */
object TextFitter {

    const val ELLIPSIS = "…"

    fun fit(text: String, budget: Int, width: (String) -> Int): FittedText {
        if (budget <= 0 || width(text) <= budget) return FittedText(text, truncated = false)

        val ellipsisWidth = width(ELLIPSIS)
        var end = text.length - 1
        while (end > 0 && width(text.substring(0, end)) + ellipsisWidth > budget) {
            end--
        }
        return FittedText(text.substring(0, end) + ELLIPSIS, truncated = true)
    }
}
