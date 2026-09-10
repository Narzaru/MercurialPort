package com.narzaru.mercurial.diff

object HgDiffTabKeys {

    private const val CHANGES_PREFIX = "changes|"

    private const val HISTORY_PREFIX = "history|"

    fun changes(key: String): String = "$CHANGES_PREFIX$key"

    fun history(key: String): String = "$HISTORY_PREFIX$key"

    fun changesKeyOf(tabKey: String): String? =
        if (tabKey.startsWith(CHANGES_PREFIX)) tabKey.removePrefix(CHANGES_PREFIX) else null
}
