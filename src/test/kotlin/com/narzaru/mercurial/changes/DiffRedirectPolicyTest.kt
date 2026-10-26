package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffRedirectPolicyTest {

    private class Signals(
        var enabled: Boolean = true,
        var fromDiff: Boolean = true,
        var eligible: Boolean = true,
        var openedBefore: Boolean = false,
        var openedAsFile: Boolean = false,
        var fromItsDiff: Boolean = false,
        var hasDiff: Boolean = true
    ) : DiffRedirectSignals {

        val asked = ArrayList<String>()

        override fun redirectEnabled(): Boolean = ask("enabled", enabled)

        override fun startedFromDiff(): Boolean = ask("fromDiff", fromDiff)

        override fun fileIsEligible(): Boolean = ask("eligible", eligible)

        override fun fileWasOpenBeforeJump(): Boolean = ask("openedBefore", openedBefore)

        override fun openedAsFileOnPurpose(): Boolean = ask("openedAsFile", openedAsFile)

        override fun cameFromItsDiff(): Boolean = ask("fromItsDiff", fromItsDiff)

        override fun fileHasDiff(): Boolean = ask("hasDiff", hasDiff)

        private fun <T> ask(name: String, value: T): T {
            asked.add(name)
            return value
        }
    }

    private fun decide(signals: Signals) = DiffRedirectPolicy.decide(signals)

    @Test
    fun `a changed file reached from a diff goes to its diff`() {
        assertEquals(DiffRedirectDecision.ShowDiff, decide(Signals()))
    }

    @Test
    fun `a file opened outside a diff is left alone`() {
        assertEquals(DiffRedirectDecision.KeepFile, decide(Signals(fromDiff = false)))
    }

    @Test
    fun `a file outside the local file system is left alone`() {
        assertEquals(DiffRedirectDecision.KeepFile, decide(Signals(eligible = false)))
    }

    @Test
    fun `a file that was already open when the jump started is left alone`() {
        assertEquals(DiffRedirectDecision.KeepFile, decide(Signals(openedBefore = true)))
    }

    @Test
    fun `a file asked for as a file is left alone`() {
        assertEquals(DiffRedirectDecision.KeepFile, decide(Signals(openedAsFile = true)))
    }

    @Test
    fun `a file reached from its own diff is left alone`() {
        assertEquals(DiffRedirectDecision.KeepFile, decide(Signals(fromItsDiff = true)))
    }

    @Test
    fun `the redirect stays off when the setting is off`() {
        assertEquals(DiffRedirectDecision.KeepFile, decide(Signals(enabled = false)))
    }

    @Test
    fun `a file without a diff is left alone`() {
        assertEquals(DiffRedirectDecision.KeepFile, decide(Signals(hasDiff = false)))
    }

    @Test
    fun `a setting that is off leaves every other signal unasked`() {
        val signals = Signals(enabled = false)
        decide(signals)
        assertEquals(listOf("enabled"), signals.asked)
    }

    @Test
    fun `a jump that did not start in a diff is answered without touching the file`() {
        val signals = Signals(fromDiff = false)
        decide(signals)
        assertTrue(signals.asked.none { it == "openedBefore" || it == "hasDiff" })
    }

    @Test
    fun `a file open before the jump is answered without consuming the opened as file mark`() {
        val signals = Signals(openedBefore = true)
        decide(signals)
        assertTrue(signals.asked.none { it == "openedAsFile" })
    }

    @Test
    fun `the diff of a file is looked up last`() {
        val signals = Signals()
        decide(signals)
        assertEquals("hasDiff", signals.asked.last())
    }
}
