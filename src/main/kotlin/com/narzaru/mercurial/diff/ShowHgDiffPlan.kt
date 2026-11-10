package com.narzaru.mercurial.diff

sealed interface ShowHgDiffTarget {

    data class ShowDiff(val path: String, val tabKey: String) : ShowHgDiffTarget

    data class LoadChanges(val path: String) : ShowHgDiffTarget

    data object None : ShowHgDiffTarget
}

object ShowHgDiffPlan {

    fun of(path: String, changesLoaded: Boolean, tabKey: String?): ShowHgDiffTarget = when {
        !changesLoaded -> ShowHgDiffTarget.LoadChanges(path)
        tabKey != null -> ShowHgDiffTarget.ShowDiff(path, tabKey)
        else -> ShowHgDiffTarget.None
    }
}
