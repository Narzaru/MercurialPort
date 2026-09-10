package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgStatusParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangesetFilesTest {

    private val renameStatus = """
        M f1.txt
        A f2new.txt
          f2.txt
        A new1.txt
        R f2.txt
        R f3.txt
    """.trimIndent()

    private val renameLog = """
        @3c167a418a26
        M f1.txt
        A f2new.txt
        A new1.txt
        R f2.txt
        R f3.txt
        * f2new.txt
        = f2.txt
    """.trimIndent()

    @Test
    fun `the log reads the same files as the status of the same revision`() {
        val fromLog = ChangesetFiles.parse(renameLog).getValue("3c167a418a26")
        val fromStatus = HgStatusParser.foldRenames(HgStatusParser.parse(renameStatus))

        assertEquals(fromStatus, fromLog)
    }

    @Test
    fun `a rename is folded into one entry that remembers the old name`() {
        val items = ChangesetFiles.parse(renameLog).getValue("3c167a418a26")

        assertEquals(
            listOf("M f1.txt", "→ f2new.txt", "A new1.txt", "R f3.txt"),
            items.map { "${it.status} ${it.path}" }
        )
        assertEquals("f2.txt", items.single { it.status == HgStatusParser.RENAMED_STATUS }.copiedFrom)
    }

    @Test
    fun `a copy keeps both files and names the source`() {
        val log = """
            @63464ce07e0b
            M f1.txt
            A f6copy.txt
            * f6copy.txt
            = f6.txt
        """.trimIndent()
        val items = ChangesetFiles.parse(log).getValue("63464ce07e0b")

        assertEquals(listOf("M f1.txt", "A f6copy.txt"), items.map { "${it.status} ${it.path}" })
        assertEquals("f6.txt", items.single { it.status == HgStatusParser.ADDED_STATUS }.copiedFrom)
    }

    @Test
    fun `several revisions come back in one answer and an empty one is still listed`() {
        val log = """
            @aaa1111
            M a.txt
            @bbb2222
            @ccc3333
            R c.txt
        """.trimIndent()
        val parsed = ChangesetFiles.parse(log)

        assertEquals(listOf("aaa1111", "bbb2222", "ccc3333"), parsed.keys.toList())
        assertEquals(listOf("M a.txt"), parsed.getValue("aaa1111").map { "${it.status} ${it.path}" })
        assertTrue(parsed.getValue("bbb2222").isEmpty())
        assertEquals(listOf("R c.txt"), parsed.getValue("ccc3333").map { "${it.status} ${it.path}" })
    }

    @Test
    fun `an empty answer asks for nothing`() {
        assertTrue(ChangesetFiles.parse("").isEmpty())
    }

    @Test
    fun `only single revision ranges are taken from the log`() {
        val nodes = listOf("rev1", "rev2", "rev3")
        val touches = mapOf(
            "rev1" to listOf("src/A.cs", "src/B.cs"),
            "rev2" to listOf("src/C.cs"),
            "rev3" to listOf("src/A.cs")
        )
        val ranges = BranchRevisions.fileRanges(nodes, touches)
        val singles = ChangesetFiles.singleRevisionRanges(ranges, emptySet())

        assertEquals(listOf("rev1", "rev2"), singles.keys.toList())
        assertEquals(DiffRange("p1(rev1)", "rev1"), singles.getValue("rev1"))
    }

    @Test
    fun `a merge revision is left to the status command`() {
        val nodes = listOf("rev1", "merge1")
        val touches = mapOf("rev1" to listOf("src/A.cs"), "merge1" to listOf("src/B.cs"))
        val ranges = BranchRevisions.fileRanges(nodes, touches)

        assertEquals(
            listOf("rev1", "merge1"),
            ChangesetFiles.singleRevisionRanges(ranges, emptySet()).keys.toList()
        )
        assertEquals(
            listOf("rev1"),
            ChangesetFiles.singleRevisionRanges(ranges, setOf("merge1")).keys.toList()
        )
    }

    @Test
    fun `the range of a prefetched revision matches the one the file list asks for`() {
        val nodes = listOf("rev1")
        val ranges = BranchRevisions.fileRanges(nodes, mapOf("rev1" to listOf("src/A.cs")))
        val singles = ChangesetFiles.singleRevisionRanges(ranges, emptySet())

        assertEquals(BranchRevisions.rangeGroups(ranges).keys.toList(), singles.values.toList())
    }
}
