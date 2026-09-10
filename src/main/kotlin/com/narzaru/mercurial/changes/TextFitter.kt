package com.narzaru.mercurial.changes

data class FittedText(val text: String, val truncated: Boolean)

object TextFitter {

    private const val ELLIPSIS = "…"

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
