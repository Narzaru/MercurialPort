package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.ChangesetsFragments
import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ComparisonSignatureTest {

    private val files = listOf(
        HgFileItem(status = "M", path = "src/a.kt", baseRev = "10", headRev = "12"),
        HgFileItem(status = "A", path = "src/b.kt")
    )

    private val stamp = ComparisonSignature.fragmentsStamp(
        widenToFragments = true, fragments = ChangesetsFragments.PLATFORM, policyName = "DEFAULT"
    )

    private fun signature(
        files: List<HgFileItem> = this.files,
        mode: HgDisplayMode = HgDisplayMode.CHANGESETS,
        baseRev: String = "10",
        fragmentsStamp: String = stamp
    ) = ComparisonSignature.of(files, mode, baseRev, fragmentsStamp)

    @Test
    fun `the same input gives the same signature`() {
        assertEquals(signature(), signature())
    }

    @Test
    fun `the display mode changes the signature`() {
        assertNotEquals(signature(), signature(mode = HgDisplayMode.BRANCH))
    }

    @Test
    fun `the base revision changes the signature`() {
        assertNotEquals(signature(), signature(baseRev = "11"))
    }

    @Test
    fun `the set of files changes the signature`() {
        assertNotEquals(signature(), signature(files = files.take(1)))
        assertNotEquals(signature(), signature(files = emptyList()))
    }

    @Test
    fun `a changed status or revision of a file changes the signature`() {
        assertNotEquals(signature(), signature(files = listOf(files[0].copy(status = "R"), files[1])))
        assertNotEquals(signature(), signature(files = listOf(files[0].copy(headRev = "13"), files[1])))
    }

    @Test
    fun `widening to fragments changes the stamp`() {
        assertEquals(
            ComparisonSignature.LINES_STAMP,
            ComparisonSignature.fragmentsStamp(false, ChangesetsFragments.PLATFORM, "DEFAULT")
        )
        assertNotEquals(ComparisonSignature.LINES_STAMP, stamp)
    }

    @Test
    fun `each fragments setting gives its own stamp`() {
        val stamps = ChangesetsFragments.entries
            .map { ComparisonSignature.fragmentsStamp(true, it, "DEFAULT") }

        assertEquals(stamps.size, stamps.toSet().size)
    }

    @Test
    fun `the comparison policy changes the stamp`() {
        assertNotEquals(
            ComparisonSignature.fragmentsStamp(true, ChangesetsFragments.PLATFORM, "DEFAULT"),
            ComparisonSignature.fragmentsStamp(true, ChangesetsFragments.PLATFORM, "TRIM_WHITESPACES")
        )
    }

    @Test
    fun `the fragments stamp reaches the signature`() {
        assertNotEquals(signature(), signature(fragmentsStamp = ComparisonSignature.LINES_STAMP))
    }
}
