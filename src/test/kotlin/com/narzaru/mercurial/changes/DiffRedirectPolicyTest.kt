package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffRedirectPolicyTest {

    private class Signals(
        var eligible: Boolean = true,
        var restored: Boolean = false,
        var openedAsFile: Boolean = false,
        var fromItsDiff: Boolean = false,
        var enabled: Boolean = true,
        var tabKey: String? = "key",
        var caret: Int? = 12
    ) : DiffRedirectSignals {

        val asked = ArrayList<String>()

        override fun fileIsEligible(): Boolean = ask("eligible", eligible)

        override fun tabRestoredBySession(): Boolean = ask("restored", restored)

        override fun openedAsFileOnPurpose(): Boolean = ask("openedAsFile", openedAsFile)

        override fun cameFromItsDiff(): Boolean = ask("fromItsDiff", fromItsDiff)

        override fun redirectEnabled(): Boolean = ask("enabled", enabled)

        override fun diffTabKey(): String? = ask("tabKey", tabKey)

        override fun caretLine(): Int? = ask("caret", caret)

        private fun <T> ask(name: String, value: T): T {
            asked.add(name)
            return value
        }
    }

    private fun decide(signals: Signals) = DiffRedirectPolicy.decide(signals)

    @Test
    fun `a changed file opened plainly goes to its diff`() {
        assertEquals(DiffRedirectDecision.ShowDiff("key", 12), decide(Signals()))
    }

    @Test
    fun `a file outside the local file system is left alone`() {
        assertEquals(DiffRedirectDecision.KeepFile, decide(Signals(eligible = false)))
    }

    @Test
    fun `a tab restored after a restart is left alone`() {
        assertEquals(DiffRedirectDecision.KeepFile, decide(Signals(restored = true)))
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
        assertEquals(DiffRedirectDecision.KeepFile, decide(Signals(tabKey = null)))
    }

    @Test
    fun `the first line carries no caret over`() {
        assertEquals(DiffRedirectDecision.ShowDiff("key", null), decide(Signals(caret = 0)))
    }

    @Test
    fun `an unknown caret line carries nothing over`() {
        assertEquals(DiffRedirectDecision.ShowDiff("key", null), decide(Signals(caret = null)))
    }

    @Test
    fun `a restored tab is answered without consuming the opened as file mark`() {
        val signals = Signals(restored = true)
        decide(signals)
        assertTrue(signals.asked.none { it == "openedAsFile" })
    }

    @Test
    fun `the diff of a file is looked up only once the setting allows it`() {
        val signals = Signals(enabled = false)
        decide(signals)
        assertTrue(signals.asked.none { it == "tabKey" })
    }
}
