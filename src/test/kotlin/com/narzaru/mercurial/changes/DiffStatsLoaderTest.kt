package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgBytesResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffStatsLoaderTest {

    private val diffOfA = """
        diff --git a/src/A.kt b/src/A.kt
        --- a/src/A.kt
        +++ b/src/A.kt
        @@ -1,2 +1,2 @@
        -old
        +new
        +more
    """.trimIndent()

    private val warnings = ArrayList<String>()

    private fun loader(hg: FakeHg, hashes: Map<String, String> = emptyMap()) =
        DiffStatsLoader(hg, ParallelJobs(SameThreadLauncher), { hashes[it] }, { warnings.add(it) })

    private fun diff(base: String, head: String = "", vararg paths: String) =
        DiffStatsPlan.arguments(DiffRange(base, head), paths.toList(), false)

    @Test
    fun `without ranges the target revision is diffed against the working directory`() {
        val hg = FakeHg(mapOf(diff(".") to ok(diffOfA)))

        val stats = loader(hg).countAll(".", emptyList(), emptyMap(), false)

        assertEquals(listOf(diff(".")), hg.calls)
        val byPath = stats.getValue(DiffRange(".", ""))!!
        assertEquals(2, byPath.getValue("src/A.kt").added)
        assertEquals(1, byPath.getValue("src/A.kt").removed)
    }

    @Test
    fun `each range is diffed with its own paths`() {
        val first = DiffRange("p1", "n1")
        val second = DiffRange("n1", "n2")
        val hg = FakeHg(
            mapOf(
                diff("p1", "n1", "src/A.kt") to ok(diffOfA),
                diff("n1", "n2") to ok("")
            )
        )

        val stats = loader(hg).countAll(".", listOf(first, second), mapOf(first to listOf("src/A.kt")), false)

        assertEquals(listOf(first, second), stats.keys.toList())
        assertEquals(setOf("src/A.kt"), stats.getValue(first)!!.keys)
        assertEquals(emptyMap<String, Any>(), stats.getValue(second))
    }

    @Test
    fun `a failed range is kept as unavailable and warned about`() {
        val failed = DiffRange("p1", "n1")
        val counted = DiffRange("n1", "n2")
        val hg = FakeHg(mapOf(diff("n1", "n2") to ok(diffOfA)))

        val stats = loader(hg).countAll(".", listOf(failed, counted), emptyMap(), false)

        assertTrue(failed in stats)
        assertNull(stats[failed])
        assertEquals(setOf("src/A.kt"), stats.getValue(counted)!!.keys)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().startsWith("Diff stats are unavailable: "))
    }

    @Test
    fun `a single range is counted for the given paths`() {
        val range = DiffRange(".", "")
        val hg = FakeHg(mapOf(DiffStatsPlan.arguments(range, listOf("src/A.kt"), true) to ok(diffOfA)))

        val byPath = loader(hg).count(range, listOf("src/A.kt"), true)

        assertEquals(setOf("src/A.kt"), byPath!!.keys)
    }

    @Test
    fun `a failed count is unavailable`() {
        assertNull(loader(FakeHg(emptyMap())).count(DiffRange(".", ""), listOf("src/A.kt"), false))
        assertEquals(1, warnings.size)
    }

    @Test
    fun `content hashes skip files that cannot be read`() {
        val hashes = loader(FakeHg(emptyMap()), mapOf("new/a.txt" to "c:1"))
            .contentHashes(listOf("new/a.txt", "new/gone.txt"))

        assertEquals(mapOf("new/a.txt" to "c:1"), hashes)
    }

    private fun ok(stdout: String) = HgBytesResult(0, stdout.toByteArray(Charsets.UTF_8), "")

    private class FakeHg(private val answers: Map<List<String>, HgBytesResult>) : HgDiffCommands {

        val calls = ArrayList<List<String>>()

        @Synchronized
        override fun run(args: List<String>): HgBytesResult {
            calls.add(args)
            return answers[args] ?: HgBytesResult(1, ByteArray(0), "no answer prepared for $args")
        }
    }
}
