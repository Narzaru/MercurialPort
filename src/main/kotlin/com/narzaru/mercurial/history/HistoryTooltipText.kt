package com.narzaru.mercurial.history

object HistoryTooltipText {

    fun wrapped(text: String, maxWidthPx: Int, textWidthPx: Int): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val body = escape(trimmed)
        if (textWidthPx <= maxWidthPx) return "<html><body>$body</body></html>"
        return "<html><body><div width=\"$maxWidthPx\">$body</div></body></html>"
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (ch in text) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(ch)
        }
    }
}
