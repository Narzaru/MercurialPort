package com.narzaru.mercurial.diff

import com.intellij.diff.impl.DiffSettingsHolder.IncludeInNavigationHistory

object DiffHistoryScope {

    fun keepsPlaces(setting: IncludeInNavigationHistory): Boolean =
        setting != IncludeInNavigationHistory.Never
}
