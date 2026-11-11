package com.narzaru.mercurial.review

import com.narzaru.mercurial.changes.DiffFragment
import com.narzaru.mercurial.changes.HgCommands
import com.narzaru.mercurial.changes.LineRange
import com.narzaru.mercurial.hg.HgResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewChangesTest {

    private val repo = FakeReviewRepo()

    private val base = (1..10).map { "line $it" }

    private fun text(lines: List<String>) = lines.joinToString("\n", postfix = "\n")

    private fun edited(vararg changes: Pair<Int, String>, from: List<String> = base): List<String> {
        val lines = from.toMutableList()
        for ((number, line) in changes) lines[number - 1] = line
        return lines
    }

    private fun describe(files: List<ReviewFile>) =
        files.map { "${it.type} ${it.oldPath}>${it.path} ${it.baseRev}..${it.headRev}" }

    private fun shown(diff: ReviewFileDiff) = diff.hunks.map { "${it.fragment.right.start + 1}:${it.shown}" }

    @Test
    fun `the file list holds what non-merge revisions changed and drops files back to where they were`() {
        repo.commit("d0", null, changes = mapOf("A.kt" to text(base), "E.kt" to "e\n", "Gone.kt" to "g\n"))
        repo.commit(
            "f1", "d0", "feature",
            changes = mapOf("A.kt" to text(edited(2 to "f1")), "E.kt" to "changed\n", "New.kt" to "a\nb\n", "Gone.kt" to null)
        )
        repo.commit("f2", "f1", "feature", changes = mapOf("E.kt" to "e\n"))

        val load = ReviewChanges(repo).load(listOf("f2", "f1"))

        assertNull(load.error)
        assertEquals(
            listOf("MODIFIED A.kt>A.kt d0..f1", "DELETED Gone.kt>Gone.kt d0..f1", "ADDED New.kt>New.kt d0..f1"),
            describe(load.files)
        )
        assertEquals(1, repo.count("log"))
    }

    @Test
    fun `an added file shows one more line than it has`() {
        repo.commit("d0", null, changes = mapOf("A.kt" to text(base)))
        repo.commit("f1", "d0", "feature", changes = mapOf("New.kt" to "a\nb\nc\n"))
        val changes = ReviewChanges(repo)
        val load = changes.load(listOf("f1"))

        val diff = changes.diff(load, load.files.single())

        assertEquals(emptyList<String>(), diff.oldLines)
        assertEquals(listOf(DiffFragment(LineRange(0, 0), LineRange(0, 4))), diff.hunks.map { it.fragment })
    }

    @Test
    fun `a merge from the base branch adds only files that differ from both parents`() {
        repo.commit("d0", null, changes = mapOf("A.kt" to text(base), "B.kt" to text(base)))
        repo.commit("f1", "d0", "feature", changes = mapOf("A.kt" to text(edited(2 to "f1"))))
        repo.commit("d1", "d0", changes = mapOf("B.kt" to text(edited(8 to "d1"))))
        repo.merge("m1", "f1", "d1", "feature", mapOf("B.kt" to text(edited(8 to "d1"))))
        repo.commit("f2", "m1", "feature", changes = mapOf("A.kt" to text(edited(2 to "f1", 5 to "f2"))))

        val load = ReviewChanges(repo).load(listOf("f1", "m1", "f2"))

        assertEquals(listOf("MODIFIED A.kt>A.kt d0..f2"), describe(load.files))
        assertEquals(2, repo.count("status"))
    }

    @Test
    fun `a merge from another branch touches what differs from its first parent`() {
        repo.commit("d0", null, changes = mapOf("A.kt" to text(base), "src/B.kt" to text(base), "C.kt" to text(base)))
        repo.commit("f1", "d0", "feature", changes = mapOf("A.kt" to text(edited(2 to "f1"))))
        repo.commit("x1", "d0", "release", changes = mapOf("src/B.kt" to text(edited(8 to "x1")), "C.kt" to text(edited(8 to "x1"))))
        repo.merge("m1", "f1", "x1", "feature", mapOf("src/B.kt" to text(edited(8 to "x1")), "C.kt" to text(edited(8 to "x1"))))
        repo.commit("f2", "m1", "feature", changes = mapOf("src/B.kt" to text(edited(2 to "f2", 8 to "x1"))))

        val load = ReviewChanges(repo).load(listOf("f1", "m1", "f2"))

        assertEquals(listOf("MODIFIED A.kt>A.kt d0..f1", "MODIFIED src/B.kt>src/B.kt f1..f2"), describe(load.files))
        assertEquals(listOf("m1", "f2"), load.files[1].revisions)
        assertEquals(1, repo.count("status"))
    }

    @Test
    fun `the old side is read before the first change under the name the file had then`() {
        repo.commit("d0", null, changes = mapOf("Old.kt" to text(base)))
        repo.commit("f1", "d0", "feature", changes = mapOf("Old.kt" to text(edited(2 to "f1"))))
        repo.commit("f2", "f1", "feature", renames = mapOf("New.kt" to "Old.kt"))
        repo.commit("f3", "f2", "feature", changes = mapOf("New.kt" to text(edited(2 to "f1", 9 to "f3"))))
        val changes = ReviewChanges(repo)
        val load = changes.load(listOf("f1", "f2", "f3"))

        val file = load.files.single()
        val diff = changes.diff(load, file)

        assertEquals(listOf("RENAMED Old.kt>New.kt d0..f3"), describe(load.files))
        assertEquals(listOf("f1", "f2", "f3"), file.revisions)
        assertEquals(base + "", diff.oldLines)
        assertEquals(listOf("2:true", "9:true"), shown(diff))
    }

    @Test
    fun `lines a merge brought from both sides are hidden and the review's own lines stay`() {
        repo.commit("d0", null, changes = mapOf("A.kt" to text(base)))
        repo.commit("f1", "d0", "feature", changes = mapOf("A.kt" to text(edited(2 to "f1"))))
        repo.commit("d1", "d0", changes = mapOf("A.kt" to text(edited(8 to "d1"))))
        repo.merge("m1", "f1", "d1", "feature", mapOf("A.kt" to text(edited(2 to "f1", 8 to "d1"))))
        repo.commit("f2", "m1", "feature", changes = mapOf("A.kt" to text(edited(2 to "f1", 5 to "f2", 8 to "d1"))))
        val changes = ReviewChanges(repo)
        val load = changes.load(listOf("f1", "m1", "f2"))

        val diff = changes.diff(load, load.files.single())

        assertEquals(listOf("f1", "m1", "f2"), diff.file.revisions)
        assertEquals(listOf("2:true", "5:true", "8:false"), shown(diff))
        assertEquals(listOf(ReviewStepKind.REVIEW, ReviewStepKind.REVIEW_MERGE, ReviewStepKind.REVIEW), diff.steps.map { it.kind })
        assertEquals(edited(2 to "f1", 5 to "f2", 8 to "d1") + "", diff.newLines)
        assertEquals(edited(8 to "d1") + "", diff.leftSide())
    }

    @Test
    fun `lines a merge took cleanly from one parent stay shown`() {
        repo.commit("d0", null, changes = mapOf("C.kt" to text(base), "A.kt" to text(base)))
        repo.commit("f1", "d0", "feature", changes = mapOf("A.kt" to text(edited(2 to "f1"))))
        repo.commit("x1", "d0", "release", changes = mapOf("C.kt" to text(edited(8 to "x1"))))
        repo.merge("m1", "f1", "x1", "feature", mapOf("C.kt" to text(edited(8 to "x1"))))
        repo.commit("f2", "m1", "feature", changes = mapOf("C.kt" to text(edited(2 to "f2", 8 to "x1"))))
        val changes = ReviewChanges(repo)
        val load = changes.load(listOf("f1", "m1", "f2"))
        val file = load.files.single { it.path == "C.kt" }

        val diff = changes.diff(load, file)

        assertEquals("f1", file.baseRev)
        assertTrue(changes.needsTrace(load, file))
        assertEquals(listOf("2:true", "8:true"), shown(diff))
        assertFalse(diff.steps.first().unrelated)
    }

    @Test
    fun `a merge that left the file as its first parent had it is passed without reading the file`() {
        repo.commit("d0", null, changes = mapOf("A.kt" to text(base), "B.kt" to text(base)))
        repo.commit("f1", "d0", "feature", changes = mapOf("A.kt" to text(edited(2 to "f1"))))
        repo.commit("d1", "d0", changes = mapOf("B.kt" to text(edited(8 to "d1"))))
        repo.merge("m1", "f1", "d1", "feature", mapOf("B.kt" to text(edited(8 to "d1"))))
        repo.commit("f2", "m1", "feature", changes = mapOf("A.kt" to text(edited(2 to "f1", 5 to "f2"))))
        val changes = ReviewChanges(repo)
        val load = changes.load(listOf("f1", "m1", "f2"))

        val diff = changes.diff(load, load.files.single())

        assertEquals(listOf("2:true", "5:true"), shown(diff))
        assertEquals(listOf("f1", "f2"), diff.steps.map { it.node })
        assertFalse(repo.calls.contains(listOf("cat", "-r", "m1", "path:A.kt")))
    }

    @Test
    fun `the graph between the sides is read once for all traced files`() {
        repo.commit("d0", null, changes = mapOf("A.kt" to text(base), "B.kt" to text(base)))
        repo.commit("f1", "d0", "feature", changes = mapOf("A.kt" to text(edited(2 to "f1")), "B.kt" to text(edited(2 to "f1"))))
        repo.commit("d1", "d0", changes = mapOf("A.kt" to text(edited(8 to "d1")), "B.kt" to text(edited(8 to "d1"))))
        repo.merge(
            "m1", "f1", "d1", "feature",
            mapOf("A.kt" to text(edited(2 to "f1", 8 to "d1")), "B.kt" to text(edited(2 to "f1", 8 to "d1")))
        )
        repo.commit(
            "f2", "m1", "feature",
            changes = mapOf("A.kt" to text(edited(2 to "f2", 8 to "d1")), "B.kt" to text(edited(2 to "f2", 8 to "d1")))
        )
        val changes = ReviewChanges(repo)
        val load = changes.load(listOf("f1", "m1", "f2"))

        val diffs = changes.diffs(load, load.files)

        assertEquals(listOf(listOf("2:true", "8:false"), listOf("2:true", "8:false")), diffs.map { shown(it) })
        assertEquals(2, repo.count("log"))
    }

    @Test
    fun `a rename that keeps most of the file stays one entry`() {
        repo.commit("d0", null, changes = mapOf("Similar.kt" to text(base)))
        repo.commit(
            "f1", "d0", "feature",
            changes = mapOf("Moved.kt" to text(edited(3 to "changed"))),
            renames = mapOf("Moved.kt" to "Similar.kt")
        )

        val load = ReviewChanges(repo).load(listOf("f1"))

        assertEquals(listOf("RENAMED Similar.kt>Moved.kt d0..f1"), describe(load.files))
    }

    @Test
    fun `a rename that rewrites the file is shown as a removal and an addition`() {
        repo.commit("d0", null, changes = mapOf("Rewritten.kt" to text(base)))
        repo.commit(
            "f1", "d0", "feature",
            changes = mapOf("Fresh.kt" to text(base.map { "new $it" })),
            renames = mapOf("Fresh.kt" to "Rewritten.kt")
        )

        val load = ReviewChanges(repo).load(listOf("f1"))

        assertEquals(
            listOf("ADDED Fresh.kt>Fresh.kt d0..f1", "DELETED Rewritten.kt>Rewritten.kt d0..f1"),
            describe(load.files)
        )
    }

    @Test
    fun `the rename threshold is an option`() {
        repo.commit("d0", null, changes = mapOf("Similar.kt" to text(base)))
        repo.commit(
            "f1", "d0", "feature",
            changes = mapOf("Moved.kt" to text(edited(3 to "changed"))),
            renames = mapOf("Moved.kt" to "Similar.kt")
        )

        val load = ReviewChanges(repo, ReviewOptions(renameSimilarity = 0.95)).load(listOf("f1"))

        assertEquals(listOf("ADDED", "DELETED"), load.files.map { it.type.name })
    }

    @Test
    fun `a binary file is listed without hunks`() {
        repo.commit("d0", null, changes = mapOf("A.kt" to text(base)))
        repo.commit("f1", "d0", "feature", changes = mapOf("logo.png" to "PNG\u0000\u0001"))
        val changes = ReviewChanges(repo)
        val load = changes.load(listOf("f1"))

        val file = load.files.single()

        assertTrue(file.binary)
        assertEquals(emptyList<ReviewHunk>(), changes.diff(load, file).hunks)
    }

    @Test
    fun `a failed log is reported instead of a file list`() {
        val failing = ReviewChanges(HgCommands { HgResult(255, "", "abort: unknown revision 'zz'") })

        val load = failing.load(listOf("zz"))

        assertTrue(load.error!!.contains("unknown revision"))
        assertEquals(emptyList<ReviewFile>(), load.files)
    }
}
