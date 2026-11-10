package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgContentCache
import com.narzaru.mercurial.hg.HgResult
import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffSidesReaderTest {

    private val head = "a\nB2\nC2\nd"

    private val patchOfFirst = """
        diff --git a/a.txt b/a.txt
        --- a/a.txt
        +++ b/a.txt
        @@ -1,4 +1,4 @@
         a
        -b
        +B2
         c
         d
    """.trimIndent()

    private val patchOfSecond = """
        diff --git a/a.txt b/a.txt
        --- a/a.txt
        +++ b/a.txt
        @@ -1,4 +1,4 @@
         a
         B2
        -c
        +C2
         d
    """.trimIndent()

    private val changesetItem = HgFileItem(status = "M", path = "a.txt", headRev = "n2")

    private val catOfHead = listOf("cat", "-r", "n2", "a.txt")
    private val catOfLastChangeBase = listOf("cat", "-r", "p1(n2)", "a.txt")
    private val diffOfFirst = listOf("diff", "--git", "-c", "n1", "path:a.txt")
    private val diffOfSecond = listOf("diff", "--git", "-c", "n2", "path:a.txt")

    @Test
    fun `a working copy file is compared with the base revision`() {
        val hg = FakeHg(mapOf(listOf("cat", "-r", ".", "a.txt") to ok("base")))
        val sides = reader(hg).read(HgFileItem(status = "M", path = "a.txt"), ".")

        assertEquals(listOf(listOf("cat", "-r", ".", "a.txt")), hg.calls)
        assertEquals("base", sides.base)
        assertNull(sides.head)
        assertFalse(sides.plan.rightIsRevision)
        assertFalse(sides.onlyLast)
        assertEquals(".", sides.baseTitleRev)
        assertEquals(0, sides.skippedHunks)
    }

    @Test
    fun `the base revision of the file wins over the panel one`() {
        val hg = FakeHg(mapOf(listOf("cat", "-r", "7", "a.txt") to ok("base")))
        val sides = reader(hg).read(HgFileItem(status = "M", path = "a.txt", baseRev = "7"), ".")

        assertEquals(listOf(listOf("cat", "-r", "7", "a.txt")), hg.calls)
        assertEquals("7", sides.baseTitleRev)
    }

    @Test
    fun `a new working copy file asks hg for nothing`() {
        val hg = FakeHg(emptyMap())
        val sides = reader(hg).read(HgFileItem(status = "A", path = "a.txt"), ".")

        assertTrue(hg.calls.isEmpty())
        assertNull(sides.base)
        assertNull(sides.head)
    }

    @Test
    fun `a changeset file is rolled back from its head revision`() {
        val hg = FakeHg(
            mapOf(catOfHead to ok(head), diffOfFirst to ok(patchOfFirst), diffOfSecond to ok(patchOfSecond))
        )
        val sides = reader(hg).read(changesetItem, "p1(n1)")

        assertEquals(listOf(catOfHead, diffOfSecond, diffOfFirst), hg.calls)
        assertEquals("a\nb\nc\nd", sides.base)
        assertEquals(head, sides.head)
        assertTrue(sides.plan.rightIsRevision)
        assertFalse(sides.onlyLast)
        assertEquals("p1(n1)", sides.baseTitleRev)
    }

    @Test
    fun `a branch base that cannot be built falls back to the last change`() {
        val hg = FakeHg(
            mapOf(catOfHead to ok(head), diffOfSecond to failed("unknown revision"), catOfLastChangeBase to ok("last"))
        )
        val sides = reader(hg).read(changesetItem, "p1(n1)")

        assertEquals(listOf(catOfHead, diffOfSecond, catOfLastChangeBase), hg.calls)
        assertEquals("last", sides.base)
        assertEquals(head, sides.head)
        assertTrue(sides.onlyLast)
        assertEquals("p1(n2)", sides.baseTitleRev)
    }

    @Test
    fun `a file added inside the range has only the head side`() {
        val hg = FakeHg(mapOf(catOfHead to ok(head)))
        val sides = reader(hg).read(changesetItem.copy(status = "A"), "p1(n1)")

        assertEquals(listOf(catOfHead), hg.calls)
        assertNull(sides.base)
        assertEquals(head, sides.head)
        assertFalse(sides.onlyLast)
    }

    @Test
    fun `skipped hunks are remembered when the base comes from the cache`() {
        val hg = FakeHg(
            mapOf(catOfHead to ok("xxx\nyyy"), diffOfFirst to ok(""), diffOfSecond to ok(patchOfSecond))
        )
        val cache = HgContentCache()
        val skipped = HashMap<String, Int>()
        val first = reader(hg, cache, skipped).read(changesetItem, "p1(n1)")
        val second = reader(hg, cache, skipped).read(changesetItem, "p1(n1)")

        assertEquals(listOf(catOfHead, diffOfSecond, diffOfFirst), hg.calls)
        assertEquals(1, first.skippedHunks)
        assertEquals(1, second.skippedHunks)
        assertEquals(first.base, second.base)
    }

    @Test
    fun `a base that fits again forgets the skipped hunks`() {
        val hg = FakeHg(
            mapOf(catOfHead to ok(head), diffOfFirst to ok(patchOfFirst), diffOfSecond to ok(patchOfSecond))
        )
        val skipped = hashMapOf("a.txt" to 2)
        val sides = reader(hg, skipped = skipped).read(changesetItem, "p1(n1)")

        assertEquals(0, sides.skippedHunks)
        assertTrue(skipped.isEmpty())
    }

    private fun reader(
        hg: FakeHg,
        cache: HgContentCache = HgContentCache(),
        skipped: MutableMap<String, Int> = HashMap()
    ) = DiffSidesReader(
        BranchBaseBuilder(
            hg,
            cache,
            ComparisonState(
                baseRev = "p1(n1)",
                mode = HgDisplayMode.CHANGESETS,
                changesetRevs = mapOf("a.txt" to listOf("n1", "n2"))
            ),
            FragmentSettings(widenToFragments = false, fragmentsFromHg = false, stamp = "stamp"),
            NoLines,
            {}
        ),
        skipped
    )

    private fun ok(stdout: String) = HgResult(0, stdout, "")

    private fun failed(stderr: String) = HgResult(1, "", stderr)

    private class FakeHg(private val answers: Map<List<String>, HgResult>) : HgCommands {

        val calls = ArrayList<List<String>>()

        override fun run(args: List<String>): HgResult {
            calls.add(args)
            return answers[args] ?: HgResult(1, "", "no answer prepared for $args")
        }
    }

    private object NoLines : LineComparator {

        override fun changedLines(left: String, right: String): List<DiffFragment> = emptyList()

        override fun fragmentLines(left: String, right: String): List<DiffFragment> = emptyList()
    }
}
