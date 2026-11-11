package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.ChangesetsEngine

data class ChangesetsDiffControls(val widenEnabled: Boolean, val fragmentsEnabled: Boolean) {

    companion object {

        fun of(engine: ChangesetsEngine, widenToFragments: Boolean): ChangesetsDiffControls {
            val ownRules = !engine.isUpsourceReview
            return ChangesetsDiffControls(widenEnabled = ownRules, fragmentsEnabled = ownRules && widenToFragments)
        }
    }
}
