package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchRevisionsTest {

    private val log = listOf(
        "2547|d1d82b2|-1|Ivanov|2026-08-10 11:02 +0300|PSA-12517 fpz in zones",
        "2583|8fcae09|2582|Ivanov|2026-08-12 09:14 +0300|Merge with PSA-12506-new-properties",
        "2584|97fbf27|-1|Ivanov|2026-08-12 10:01 +0300|PSA-12517 zone properties",
        "2610|38e0420|2609|Ivanov|2026-08-14 18:20 +0300|Merge with default",
        "2611|702a733|-1|Ivanov|2026-08-14 18:41 +0300|PSA-12506 zones in the tree"
    ).joinToString("\n", postfix = "\n")

    private val revisions = BranchRevisions.parse(log)

    @Test
    fun `parse reads revisions oldest first and marks the merges`() {
        assertEquals(5, revisions.size)
        assertEquals(listOf("2547", "2583", "2584", "2610", "2611"), revisions.map { it.rev })
        assertEquals(listOf(false, true, false, true, false), revisions.map { it.isMerge })
        assertEquals("PSA-12517 fpz in zones", revisions[0].summary)
        assertEquals("Ivanov", revisions[0].author)
    }

    @Test
    fun `broken lines are skipped`() {
        assertEquals(emptyList<HgRevision>(), BranchRevisions.parse("2547|d1d82b2\n\n"))
    }

    @Test
    fun `merges are out of the default selection`() {
        assertEquals(
            listOf("d1d82b2", "97fbf27", "702a733"),
            RevisionSelection.DEFAULT.selectedNodes(revisions)
        )
        assertTrue(RevisionSelection.DEFAULT.isDefaultFor(revisions))
    }

    @Test
    fun `an override switches a single revision and is remembered`() {
        val selection = RevisionSelection.DEFAULT
            .with(revisions[1], true)
            .with(revisions[0], false)

        assertEquals(listOf("8fcae09", "97fbf27", "702a733"), selection.selectedNodes(revisions))
        assertFalse(selection.isDefaultFor(revisions))

        val restored = RevisionSelection.fromStorage(selection.toStorage())
        assertEquals(selection.selectedNodes(revisions), restored.selectedNodes(revisions))
    }

    @Test
    fun `switching a revision back to its default drops the override`() {
        val selection = RevisionSelection.DEFAULT.with(revisions[0], false).with(revisions[0], true)

        assertTrue(selection.isDefaultFor(revisions))
        assertEquals(emptyList<String>(), selection.toStorage())
    }

    @Test
    fun `stored garbage does not become a selection`() {
        val selection = RevisionSelection.fromStorage(listOf("", "junk", "=1"))

        assertTrue(selection.isDefaultFor(revisions))
    }

    @Test
    fun `base is the first parent of the oldest selected revision`() {
        assertEquals(
            "p1(min(d1d82b2+97fbf27+702a733))",
            BranchRevisions.baseRevset(RevisionSelection.DEFAULT.selectedNodes(revisions))
        )
        assertEquals(
            "p1(min(97fbf27+702a733))",
            BranchRevisions.baseRevset(
                RevisionSelection.DEFAULT.with(revisions[0], false).selectedNodes(revisions)
            )
        )
    }

    @Test
    fun `long summaries are cut for the checkbox and merges are labelled`() {
        val long = revisions[0].copy(summary = "x".repeat(80))

        assertEquals("d1d82b2  " + "x".repeat(19) + "…", BranchRevisions.chipText(long, limit = 20))
        assertTrue(BranchRevisions.chipText(revisions[1]).contains("merge Merge with"))
    }

    private val filesLog = """
        @d1d82b2
        src/A.cs
        src/B.cs
        @97fbf27
        src/B.cs
        @702a733
        src/C.cs
    """.trimIndent() + "\n"

    @Test
    fun `file touches are read per revision`() {
        val touches = BranchRevisions.parseFileTouches(filesLog)

        assertEquals(listOf("d1d82b2", "97fbf27", "702a733"), touches.keys.toList())
        assertEquals(listOf("src/A.cs", "src/B.cs"), touches["d1d82b2"])
        assertEquals(listOf("src/C.cs"), touches["702a733"])
    }

    @Test
    fun `a revision that touched nothing gives an empty block`() {
        val touches = BranchRevisions.parseFileTouches("@d1d82b2\n@97fbf27\nsrc/B.cs\n")

        assertEquals(emptyList<String>(), touches.getValue("d1d82b2"))
        assertEquals(listOf("src/B.cs"), touches["97fbf27"])
    }

    @Test
    fun `each file gets its own pair of revisions`() {
        val nodes = listOf("d1d82b2", "97fbf27", "702a733")
        val ranges = BranchRevisions.fileRanges(nodes, BranchRevisions.parseFileTouches(filesLog))

        assertEquals(setOf("src/a.cs", "src/b.cs", "src/c.cs"), ranges.keys)
        assertEquals(
            FileRange("d1d82b2", "d1d82b2", "src/A.cs", listOf("d1d82b2")),
            ranges.getValue("src/a.cs")
        )
        assertEquals(
            FileRange("d1d82b2", "97fbf27", "src/B.cs", listOf("d1d82b2", "97fbf27")),
            ranges.getValue("src/b.cs")
        )
        assertEquals("p1(702a733)", ranges.getValue("src/c.cs").base)
    }

    @Test
    fun `a selected merge moves the base without adding rows`() {
        val nodes = listOf("d1d82b2", "8fcae09", "97fbf27")
        val listed = setOf("src/a.cs", "src/b.cs")
        val touches = mapOf(
            "d1d82b2" to listOf("src/A.cs"),
            "8fcae09" to listOf("src/B.cs", "src/Merged.cs"),
            "97fbf27" to listOf("src/B.cs")
        )

        val ranges = BranchRevisions.fileRanges(nodes, touches, listed)

        assertEquals(setOf("src/a.cs", "src/b.cs"), ranges.keys)
        assertEquals("p1(8fcae09)", ranges.getValue("src/b.cs").base)
        assertEquals("97fbf27", ranges.getValue("src/b.cs").last)
        assertEquals("p1(d1d82b2)", ranges.getValue("src/a.cs").base)
    }

    @Test
    fun `unselected revisions do not extend a range`() {
        val ranges = BranchRevisions.fileRanges(
            listOf("d1d82b2"), BranchRevisions.parseFileTouches(filesLog)
        )

        assertEquals(setOf("src/a.cs", "src/b.cs"), ranges.keys)
        assertEquals("d1d82b2", ranges.getValue("src/b.cs").last)
    }

    @Test
    fun `a rename made later than the edits joins the two names into one range`() {
        val nodes = listOf("rev1", "rev2")
        val touches = mapOf(
            "rev1" to listOf("src/Old.cs"),
            "rev2" to listOf("src/Old.cs", "src/New.cs")
        )
        val ranges = BranchRevisions.fileRanges(nodes, touches)
        assertEquals(listOf("rev2"), ranges.getValue("src/new.cs").revs)

        val joined = BranchRevisions.mergeRenames(ranges, mapOf("src/new.cs" to "src/old.cs"), nodes)

        assertNotNull(joined)
        assertEquals(listOf("rev1", "rev2"), joined!!.getValue("src/new.cs").revs)
        assertEquals("p1(rev1)", joined.getValue("src/new.cs").base)
        assertEquals("rev2", joined.getValue("src/new.cs").last)
        assertEquals("src/Old.cs", joined.getValue("src/old.cs").path)
    }

    @Test
    fun `a rename made together with the edits needs no joining`() {
        val nodes = listOf("rev1")
        val ranges = BranchRevisions.fileRanges(nodes, mapOf("rev1" to listOf("src/Old.cs", "src/New.cs")))

        assertNull(BranchRevisions.mergeRenames(ranges, mapOf("src/new.cs" to "src/old.cs"), nodes))
    }

    @Test
    fun `range groups query every distinct range once and keep the file order`() {
        val nodes = listOf("rev1", "rev2", "rev3")
        val touches = mapOf(
            "rev1" to listOf("src/A.cs", "src/B.cs"),
            "rev2" to listOf("src/C.cs"),
            "rev3" to listOf("src/A.cs", "src/D.cs")
        )
        val groups = BranchRevisions.rangeGroups(BranchRevisions.fileRanges(nodes, touches))

        assertEquals(
            listOf(
                DiffRange("p1(rev1)", "rev3"),
                DiffRange("p1(rev1)", "rev1"),
                DiffRange("p1(rev2)", "rev2"),
                DiffRange("p1(rev3)", "rev3")
            ),
            groups.keys.toList()
        )
        assertEquals(setOf("src/a.cs"), groups.getValue(DiffRange("p1(rev1)", "rev3")))
        assertEquals(setOf("src/b.cs"), groups.getValue(DiffRange("p1(rev1)", "rev1")))
    }

    @Test
    fun `files sharing a range end up in a single group`() {
        val nodes = listOf("rev1")
        val touches = mapOf("rev1" to listOf("src/A.cs", "src/B.cs", "src/C.cs"))
        val groups = BranchRevisions.rangeGroups(BranchRevisions.fileRanges(nodes, touches))

        assertEquals(1, groups.size)
        assertEquals(setOf("src/a.cs", "src/b.cs", "src/c.cs"), groups.getValue(DiffRange("p1(rev1)", "rev1")))
    }

    @Test
    fun `header counts the selected revisions and says when the choice is manual`() {
        assertEquals("Revisions: 3 of 5", BranchRevisions.header(revisions, RevisionSelection.DEFAULT))
        assertEquals(
            "Revisions: 4 of 5 (custom)",
            BranchRevisions.header(revisions, RevisionSelection.DEFAULT.with(revisions[1], true))
        )
        assertEquals("Revisions: none", BranchRevisions.header(emptyList(), RevisionSelection.DEFAULT))
    }

    @Test
    fun `merge base is the newest parent branch revision the branch descends from`() {
        assertEquals(
            "max(ancestors(.) and branch('default'))",
            BranchRevisions.mergeBaseRevset("default")
        )
    }

    @Test
    fun `a quote in the branch name does not break out of the revset literal`() {
        assertEquals(
            "max(ancestors(.) and branch('it\\'s'))",
            BranchRevisions.mergeBaseRevset("it's")
        )
    }
}
