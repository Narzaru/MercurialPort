package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HgFailureTest {

    @Test
    fun `an error reported by hg keeps the usual prefix`() {
        val result = HgResult(1, "", "  abort: unknown revision \n")

        assertEquals("HG Error: abort: unknown revision", HgFailure.message(result))
    }

    @Test
    fun `a process that never started is named apart from a failing command`() {
        val result = HgResult(-1, "", "Cannot run program \"hg\"", failedToStart = true)

        assertEquals("Cannot run hg: Cannot run program \"hg\"", HgFailure.message(result))
    }

    @Test
    fun `each failure kind keeps the exit code callers already see`() {
        val results = HgFailureKind.entries.associateWith { it.result("details") }

        assertEquals(-1, results.getValue(HgFailureKind.NOT_STARTED).exitCode)
        assertEquals(-1, results.getValue(HgFailureKind.CANCELED).exitCode)
        assertEquals(-2, results.getValue(HgFailureKind.TIMED_OUT).exitCode)
        assertEquals(
            listOf(HgFailureKind.NOT_STARTED),
            results.filterValues { it.failedToStart }.keys.toList()
        )
        assertTrue(results.values.all { it.stdout.isEmpty() && it.stderr == "details" })
    }
}
