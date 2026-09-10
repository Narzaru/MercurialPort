package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.ChangesetsFragments
import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem

object ComparisonSignature {

    const val LINES_STAMP = "lines"

    fun of(
        files: List<HgFileItem>,
        mode: HgDisplayMode,
        baseRev: String,
        fragmentsStamp: String
    ): String =
        files.joinToString("|") { "${it.path}:${it.status}:${it.baseRev}:${it.headRev}" } +
            "|$mode|$baseRev|$fragmentsStamp"

    fun fragmentsStamp(
        widenToFragments: Boolean,
        fragments: ChangesetsFragments,
        policyName: String
    ): String = if (widenToFragments) "$fragments:$policyName" else LINES_STAMP
}
