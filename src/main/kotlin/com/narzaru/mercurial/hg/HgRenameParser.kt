package com.narzaru.mercurial.hg

object HgRenameParser {

    private const val MARKER = " renamed from "

    fun sourceOf(output: String): String? {
        for (line in output.split('\r', '\n')) {
            val at = line.indexOf(MARKER)
            if (at < 0) continue
            val source = line.substring(at + MARKER.length)
            val colon = source.lastIndexOf(':')
            val path = if (colon > 0) source.substring(0, colon) else source
            return path.trim().ifEmpty { null }?.let { HgPaths.normalize(it) }
        }
        return null
    }
}
