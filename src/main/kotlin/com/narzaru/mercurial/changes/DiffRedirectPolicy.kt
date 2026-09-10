package com.narzaru.mercurial.changes

interface DiffRedirectSignals {

    fun fileIsEligible(): Boolean

    fun tabRestoredBySession(): Boolean

    fun openedAsFileOnPurpose(): Boolean

    fun cameFromItsDiff(): Boolean

    fun redirectEnabled(): Boolean

    fun diffTabKey(): String?

    fun caretLine(): Int?
}

sealed interface DiffRedirectDecision {

    data object KeepFile : DiffRedirectDecision

    data class ShowDiff(val tabKey: String, val caretLine: Int?) : DiffRedirectDecision
}

object DiffRedirectPolicy {

    fun decide(signals: DiffRedirectSignals): DiffRedirectDecision {
        if (!signals.fileIsEligible()) return DiffRedirectDecision.KeepFile
        if (signals.tabRestoredBySession()) return DiffRedirectDecision.KeepFile
        if (signals.openedAsFileOnPurpose()) return DiffRedirectDecision.KeepFile
        if (signals.cameFromItsDiff()) return DiffRedirectDecision.KeepFile
        if (!signals.redirectEnabled()) return DiffRedirectDecision.KeepFile
        val tabKey = signals.diffTabKey() ?: return DiffRedirectDecision.KeepFile
        return DiffRedirectDecision.ShowDiff(tabKey, CaretTransfer.toDiff(signals.caretLine()))
    }
}
