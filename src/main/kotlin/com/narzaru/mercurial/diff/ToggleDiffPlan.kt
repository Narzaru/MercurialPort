package com.narzaru.mercurial.diff

sealed interface ToggleDiffTarget {

    data class ShowFile(val sourcePath: String) : ToggleDiffTarget

    data class ShowDiff(val tabKey: String) : ToggleDiffTarget

    data object SwapInline : ToggleDiffTarget

    data object LoadChanges : ToggleDiffTarget

    data object None : ToggleDiffTarget
}

object ToggleDiffPlan {

    fun of(
        inDiff: Boolean,
        diffSourcePath: String?,
        fileIsEligible: Boolean,
        changesLoaded: Boolean,
        tabKey: String?,
        inInlineDiff: Boolean = false
    ): ToggleDiffTarget {
        if (inInlineDiff) return ToggleDiffTarget.SwapInline
        if (inDiff) {
            val sourcePath = diffSourcePath ?: return ToggleDiffTarget.None
            return ToggleDiffTarget.ShowFile(sourcePath)
        }
        if (!fileIsEligible) return ToggleDiffTarget.None
        if (!changesLoaded) return ToggleDiffTarget.LoadChanges
        return tabKey?.let { ToggleDiffTarget.ShowDiff(it) } ?: ToggleDiffTarget.None
    }
}

enum class ToggleDiffDirection { TO_FILE, TO_DIFF }

object ToggleDiffPresentation {

    fun directionOf(target: ToggleDiffTarget, inlineShowsDiff: Boolean): ToggleDiffDirection? = when (target) {
        is ToggleDiffTarget.ShowFile -> ToggleDiffDirection.TO_FILE
        ToggleDiffTarget.SwapInline -> if (inlineShowsDiff) ToggleDiffDirection.TO_FILE else ToggleDiffDirection.TO_DIFF
        is ToggleDiffTarget.ShowDiff, ToggleDiffTarget.LoadChanges -> ToggleDiffDirection.TO_DIFF
        ToggleDiffTarget.None -> null
    }
}

object ToggleDiffPromotion {

    fun winsOverJumpToSource(target: ToggleDiffTarget): Boolean =
        target is ToggleDiffTarget.ShowDiff ||
            target is ToggleDiffTarget.SwapInline ||
            target is ToggleDiffTarget.ShowFile

    fun replacesJumpToSourceIn(diffSourcePath: String?): Boolean =
        winsOverJumpToSource(ToggleDiffPlan.of(true, diffSourcePath, false, false, null))

    fun replacesJumpToSourceInInlineDiff(): Boolean =
        winsOverJumpToSource(ToggleDiffPlan.of(false, null, false, false, null, inInlineDiff = true))
}
