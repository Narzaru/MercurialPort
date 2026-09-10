package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
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
}
