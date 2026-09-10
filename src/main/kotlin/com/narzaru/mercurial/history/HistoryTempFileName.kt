package com.narzaru.mercurial.history

import java.io.File

object HistoryTempFileName {

    private val unsafeChars = Regex("[^A-Za-z0-9._-]")

    fun of(relativePath: String, prefix: String, revision: String, author: String): String {
        val name = File(relativePath).name
        val dot = name.lastIndexOf('.')
        val base = if (dot >= 0) name.substring(0, dot) else name
        val extension = if (dot >= 0) name.substring(dot) else ""
        return "${safe(base)}_${safe(prefix)}_rev${safe(revision)}_${safe(author)}$extension"
    }

    private fun safe(text: String): String = text.replace(unsafeChars, "_")
}
