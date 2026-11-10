package com.narzaru.mercurial.history

import com.narzaru.mercurial.hg.HgPaths
import java.io.File

enum class HistoryFollowStep(val cancelsPending: Boolean) {
    IGNORE(false),
    DROP(true),
    STAY(true),
    LOAD_NOW(true),
    LOAD_DEBOUNCED(true)
}

object HistoryFollowPlan {

    fun step(
        requestedPath: String,
        shownPath: String?,
        fromEditor: Boolean,
        followSuppressed: Boolean,
        visible: Boolean
    ): HistoryFollowStep = when {
        fromEditor && followSuppressed -> HistoryFollowStep.IGNORE
        !visible -> HistoryFollowStep.DROP
        shownPath != null && keyOf(requestedPath) == keyOf(shownPath) -> HistoryFollowStep.STAY
        fromEditor -> HistoryFollowStep.LOAD_DEBOUNCED
        else -> HistoryFollowStep.LOAD_NOW
    }

    private fun keyOf(path: String): String = HgPaths.key(File(path).absolutePath)
}
