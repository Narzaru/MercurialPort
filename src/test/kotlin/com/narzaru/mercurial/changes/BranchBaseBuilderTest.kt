package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgContentCache
import com.narzaru.mercurial.hg.HgResult
import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchBaseBuilderTest {

    private val item = HgFileItem(status = "M", path = "a.txt", headRev = "n2")

    private val head = "a\nB2\nC2\nd"

    private val baseRevisionText = "a\nb\nc\nd"

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

    private val fragmentPatch = """
        diff --git a/a.txt b/a.txt
        --- a/a.txt
        +++ b/a.txt
        @@ -2,2 +2,2 @@
        -b
        -c
        +B2
        +C2
    """.trimIndent()

    private val diffOfFirst = listOf("diff", "--git", "-c", "n1", "path:a.txt")
    private val diffOfSecond = listOf("diff", "--git", "-c", "n2", "path:a.txt")
    private val catOfBase = listOf("cat", "-r", "p1(n1)", "a.txt")
    private val hgFragments =
        listOf("diff", "--git", "-U", "0", "--rev", "p1(n1)", "--rev", "n2", "path:a.txt")

    @Test
    fun `a single revision of a file is read from the base revision`() {
        val hg = FakeHg(mapOf(catOfBase to ok(baseRevisionText)))
        val base = builder(hg, revs = listOf("n1")).read(item, head)

        assertEquals(listOf(catOfBase), hg.calls)
        assertEquals(baseRevisionText, base.text)
        assertNull(base.skippedHunks)
    }

    @Test
    fun `patches of the branch are rolled back from the newest one`() {
        val hg = FakeHg(mapOf(diffOfFirst to ok(patchOfFirst), diffOfSecond to ok(patchOfSecond)))
        val base = builder(hg).read(item, head)

        assertEquals(listOf(diffOfSecond, diffOfFirst), hg.calls)
        assertEquals("a\nb\nc\nd", base.text)
        assertEquals(0, base.skippedHunks)
    }

    @Test
    fun `a patch that does not fit the file is counted as a skipped hunk`() {
        val hg = FakeHg(mapOf(diffOfFirst to ok(""), diffOfSecond to ok(patchOfSecond)))
        val base = builder(hg).read(item, "xxx\nyyy")

        assertEquals("xxx\nyyy", base.text)
        assertEquals(1, base.skippedHunks)
    }

    @Test
    fun `an empty patch leaves the text as it is`() {
        val hg = FakeHg(mapOf(diffOfFirst to ok(""), diffOfSecond to ok(patchOfSecond)))
        val base = builder(hg).read(item, head)

        assertEquals(listOf(diffOfSecond, diffOfFirst), hg.calls)
        assertEquals("a\nB2\nc\nd", base.text)
        assertEquals(0, base.skippedHunks)
    }

    @Test
    fun `widening restores the whole fragment from the base revision`() {
        val hg = FakeHg(
            mapOf(diffOfFirst to ok(""), diffOfSecond to ok(patchOfSecond), catOfBase to ok(baseRevisionText))
        )
        val lines = RecordingLines()
        val base = builder(hg, widen = true, lines = lines).read(item, head)

        assertEquals(listOf(diffOfSecond, diffOfFirst, catOfBase), hg.calls)
        assertEquals(baseRevisionText, base.text)
        assertEquals(
            listOf(
                "changed: a\nB2\nc\nd | $head",
                "fragment: $baseRevisionText | $head"
            ),
            lines.calls
        )
    }

    @Test
    fun `without widening the base is the rolled back text`() {
        val hg = FakeHg(
            mapOf(diffOfFirst to ok(""), diffOfSecond to ok(patchOfSecond), catOfBase to ok(baseRevisionText))
        )
        val base = builder(hg, widen = false).read(item, head)

        assertEquals(listOf(diffOfSecond, diffOfFirst), hg.calls)
        assertEquals("a\nB2\nc\nd", base.text)
    }

    @Test
    fun `fragments asked from hg widen the base the same way`() {
        val hg = FakeHg(
            mapOf(
                diffOfFirst to ok(""),
                diffOfSecond to ok(patchOfSecond),
                catOfBase to ok(baseRevisionText),
                hgFragments to ok(fragmentPatch)
            )
        )
        val lines = RecordingLines()
        val base = builder(hg, widen = true, fromHg = true, lines = lines).read(item, head)

        assertEquals(listOf(diffOfSecond, diffOfFirst, catOfBase, hgFragments), hg.calls)
        assertEquals(baseRevisionText, base.text)
        assertEquals(listOf("changed: a\nB2\nc\nd | $head"), lines.calls)
    }

    @Test
    fun `a file missing from the base revision keeps the rolled back text`() {
        val hg = FakeHg(
            mapOf(diffOfFirst to ok(""), diffOfSecond to ok(patchOfSecond), catOfBase to failed("no such file"))
        )
        val warnings = ArrayList<String>()
        val base = builder(hg, widen = true, warn = { warnings.add(it) }).read(item, head)

        assertEquals("a\nB2\nc\nd", base.text)
        assertTrue(warnings.single().contains("no such file"))
    }

    @Test
    fun `a file added inside the range is not looked up in the base revision`() {
        val added = HgFileItem(status = "A", path = "a.txt", headRev = "n2")
        val hg = FakeHg(mapOf(diffOfFirst to ok(""), diffOfSecond to ok(patchOfSecond)))
        val warnings = ArrayList<String>()
        val base = builder(hg, widen = true, warn = { warnings.add(it) }).read(added, head)

        assertEquals(listOf(diffOfSecond, diffOfFirst), hg.calls)
        assertEquals("a\nB2\nc\nd", base.text)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a patch that cannot be read leaves the file without a base`() {
        val hg = FakeHg(mapOf(diffOfSecond to failed("unknown revision")))
        val warnings = ArrayList<String>()
        val base = builder(hg, warn = { warnings.add(it) }).read(item, head)

        assertEquals(listOf(diffOfSecond), hg.calls)
        assertNull(base.text)
        assertTrue(warnings.single().contains("unknown revision"))
    }

    @Test
    fun `a base built once is taken from the cache without asking hg again`() {
        val hg = FakeHg(mapOf(diffOfFirst to ok(patchOfFirst), diffOfSecond to ok(patchOfSecond)))
        val cache = HgContentCache()
        val first = builder(hg, cache = cache).read(item, head)
        val second = builder(hg, cache = cache).read(item, head)

        assertEquals(listOf(diffOfSecond, diffOfFirst), hg.calls)
        assertEquals(first.text, second.text)
        assertNull(second.skippedHunks)
    }

    @Test
    fun `a revision read once is taken from the cache`() {
        val hg = FakeHg(mapOf(catOfBase to ok(baseRevisionText)))
        val cache = HgContentCache()
        val builder = builder(hg, cache = cache)

        assertEquals(baseRevisionText, builder.readRevision("p1(n1)", "a.txt"))
        assertEquals(baseRevisionText, builder.readRevision("p1(n1)", "a.txt"))
        assertEquals(listOf(catOfBase), hg.calls)
    }

    private fun builder(
        hg: FakeHg,
        revs: List<String> = listOf("n1", "n2"),
        widen: Boolean = false,
        fromHg: Boolean = false,
        cache: HgContentCache = HgContentCache(),
        lines: LineComparator = RecordingLines(),
        warn: (String) -> Unit = {}
    ) = BranchBaseBuilder(
        hg,
        cache,
        ComparisonState(
            baseRev = "p1(n1)",
            mode = HgDisplayMode.CHANGESETS,
            changesetRevs = mapOf("a.txt" to revs)
        ),
        FragmentSettings(widen, fromHg, "stamp"),
        lines,
        warn
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

    private class RecordingLines : LineComparator {

        val calls = ArrayList<String>()

        override fun changedLines(left: String, right: String): List<DiffFragment> {
            calls.add("changed: $left | $right")
            return compare(left, right)
        }

        override fun fragmentLines(left: String, right: String): List<DiffFragment> {
            calls.add("fragment: $left | $right")
            return compare(left, right)
        }

        private fun compare(left: String, right: String): List<DiffFragment> {
            val old = left.split("\n")
            val new = right.split("\n")
            var head = 0
            while (head < old.size && head < new.size && old[head] == new[head]) head++
            var tail = 0
            while (tail < old.size - head && tail < new.size - head &&
                old[old.size - 1 - tail] == new[new.size - 1 - tail]
            ) {
                tail++
            }
            if (head == old.size && head == new.size) return emptyList()
            return listOf(
                DiffFragment(LineRange(head, old.size - tail), LineRange(head, new.size - tail))
            )
        }
    }
}
