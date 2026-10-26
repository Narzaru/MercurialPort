package com.narzaru.mercurial.changes

interface DiffRedirectSignals {

    fun redirectEnabled(): Boolean

    fun startedFromDiff(): Boolean

    fun fileIsEligible(): Boolean

    fun fileWasOpenBeforeJump(): Boolean

    fun openedAsFileOnPurpose(): Boolean

    fun cameFromItsDiff(): Boolean

    fun fileHasDiff(): Boolean
}

sealed interface DiffRedirectDecision {

    data object KeepFile : DiffRedirectDecision

    data object ShowDiff : DiffRedirectDecision
}

object DiffRedirectPolicy {

    fun decide(signals: DiffRedirectSignals): DiffRedirectDecision {
        if (!signals.redirectEnabled()) return DiffRedirectDecision.KeepFile
        if (!signals.startedFromDiff()) return DiffRedirectDecision.KeepFile
        if (!signals.fileIsEligible()) return DiffRedirectDecision.KeepFile
        if (signals.fileWasOpenBeforeJump()) return DiffRedirectDecision.KeepFile
        if (signals.openedAsFileOnPurpose()) return DiffRedirectDecision.KeepFile
        if (signals.cameFromItsDiff()) return DiffRedirectDecision.KeepFile
        if (!signals.fileHasDiff()) return DiffRedirectDecision.KeepFile
        return DiffRedirectDecision.ShowDiff
    }
}
