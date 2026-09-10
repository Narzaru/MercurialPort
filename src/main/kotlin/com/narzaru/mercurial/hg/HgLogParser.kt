package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.HgHistoryItem

object HgLogParser {

    const val TEMPLATE = "{rev}|{node|short}|{author|person}|{date|isodate}|{p1rev}|{desc|firstline}"

    private const val FIELD_COUNT = 6

    fun parse(stdout: String, relativePath: String): List<HgHistoryItem> {
        val items = ArrayList<HgHistoryItem>()
        val path = HgPaths.normalize(relativePath)

        for (line in stdout.split('\r', '\n')) {
            if (line.isBlank()) continue
            val p = line.split('|', limit = FIELD_COUNT)
            if (p.size != FIELD_COUNT) continue
            items.add(
                HgHistoryItem(
                    revision = p[0], node = p[1], author = p[2], date = p[3], message = p[5],
                    path = path, parentRev = p[4]
                )
            )
        }
        return items
    }
}
