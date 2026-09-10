package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem
import java.io.File

data class ComparisonState(
    val repoRoot: File? = null,
    val baseRev: String = ".",
    val mode: HgDisplayMode = HgDisplayMode.UNCOMMITTED,
    val changesetRevs: Map<String, List<String>> = emptyMap(),
    val itemsByPath: Map<String, HgFileItem> = emptyMap()
) {

    fun revsFor(path: String, basePath: String): List<String>? =
        changesetRevs[HgPaths.key(path)] ?: changesetRevs[HgPaths.key(basePath)]

    fun changedItemAt(key: String): HgFileItem? = itemsByPath[key]?.takeIf { !it.isUnchanged }

    fun withItems(items: List<HgFileItem>): ComparisonState =
        copy(itemsByPath = items.filter { !it.isTodoItem }.associateBy { HgPaths.key(it.path) })
}
