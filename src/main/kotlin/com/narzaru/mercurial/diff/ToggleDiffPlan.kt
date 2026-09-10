package com.narzaru.mercurial.diff

sealed interface ToggleDiffTarget {

    data class ShowFile(val sourcePath: String) : ToggleDiffTarget

    data class ShowDiff(val tabKey: String) : ToggleDiffTarget

    data object LoadChanges : ToggleDiffTarget

    data object None : ToggleDiffTarget
}

object ToggleDiffPlan {

    fun of(
        inDiff: Boolean,
        diffSourcePath: String?,
        fileIsEligible: Boolean,
        changesLoaded: Boolean,
        tabKey: String?
    ): ToggleDiffTarget {
        if (inDiff) {
            val sourcePath = diffSourcePath ?: return ToggleDiffTarget.None
            return ToggleDiffTarget.ShowFile(sourcePath)
        }
        if (!fileIsEligible) return ToggleDiffTarget.None
        if (!changesLoaded) return ToggleDiffTarget.LoadChanges
        return tabKey?.let { ToggleDiffTarget.ShowDiff(it) } ?: ToggleDiffTarget.None
    }
}

object ToggleDiffPromotion {

    fun winsOverJumpToSource(target: ToggleDiffTarget): Boolean = target is ToggleDiffTarget.ShowDiff
}
