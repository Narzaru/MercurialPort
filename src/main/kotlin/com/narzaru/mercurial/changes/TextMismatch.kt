package com.narzaru.mercurial.changes

object TextMismatch {

    const val DEFAULT_WINDOW = 20

    fun describe(local: String, revision: String, window: Int = DEFAULT_WINDOW): String {
        val shared = local.commonPrefixWith(revision).length
        return "document=${local.length} chars, revision=${revision.length} chars, " +
            "first difference at $shared: document [${around(local, shared, window)}] " +
            "revision [${around(revision, shared, window)}]"
    }

    private fun around(text: String, position: Int, window: Int): String = text
        .substring((position - window).coerceAtLeast(0), (position + window).coerceAtMost(text.length))
        .map { visible(it) }
        .joinToString("")

    private fun visible(char: Char): String = when (char) {
        '\n' -> "<LF>"
        '\r' -> "<CR>"
        else -> char.toString()
    }
}
