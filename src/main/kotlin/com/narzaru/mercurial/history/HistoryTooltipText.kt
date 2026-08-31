package com.narzaru.mercurial.history

/**
 * Tooltip text for the history table.
 *
 * The table lives in a narrow tool window and `Message` is its rightmost column, so a plain
 * single-line tooltip grows to the right and runs off the screen — exactly the part that has to be
 * read. Wrapping it into a fixed-width block keeps the whole text on screen; Swing's HTML renderer
 * wraps a `div` by words once the width is set explicitly.
 */
object HistoryTooltipText {

    /**
     * Tooltip for [text]; blank text has no tooltip.
     *
     * @param maxWidthPx width the text is wrapped at
     * @param textWidthPx width [text] takes as a single line
     *
     * `width` in Swing's HTML is a fixed width, not a maximum: a short date wrapped into
     * `div width` was drawn as a long empty strip. So the block is only used for text that really
     * does not fit — everything else stays plain and the tooltip is as wide as its text.
     */
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
