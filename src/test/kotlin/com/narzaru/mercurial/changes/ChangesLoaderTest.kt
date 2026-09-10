package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgResult
import com.narzaru.mercurial.model.HgDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangesLoaderTest {

    private val revisionTemplate = StatusTextFormatter.REVISION_TEMPLATE

    private val currentRevision = listOf("log", "-r", ".", "--template", revisionTemplate)
    private val currentBranch = listOf("log", "-r", ".", "--template", "{branch}")
    private val branchRevisions =
        listOf("log", "-r", BranchRevisions.OWN_REVS, "--template", BranchRevisions.TEMPLATE + "\\n")

    private val revisionsLog = "11|n1|-1|Bob|2024-01-01 10:00 +0000|First\n" +
        "12|n2|-1|Bob|2024-01-02 10:00 +0000|Second"

    private val branchPoint = "p1(min(n1+n2))"

    private fun baseRevision(rev: String) = listOf("log", "-r", rev, "--template", revisionTemplate)

    private fun status(vararg revs: String) =
        listOf("status") + revs.flatMap { listOf("--rev", it) } + listOf("-mard", "--copies")

    @Test
    fun `uncommitted changes are read against the working directory parent`() {
        val hg = FakeHg(
            mapOf(
                currentRevision to ok("feature|abc123|Work in progress"),
                status(".") to ok("M src/A.kt\nA src/B.kt")
            )
        )
        val result = load(hg, HgDisplayMode.UNCOMMITTED)

        assertEquals(listOf(currentRevision, currentRevision, status(".")), hg.calls)
        assertEquals(".", result.targetRev)
        assertNull(result.error)
        assertEquals("Uncommitted in: feature (abc123) \"Work in progress\"", result.statusText)
        assertEquals(listOf("M src/A.kt", "A src/B.kt"), result.files.map { "${it.status} ${it.path}" })
        assertEquals(listOf(DiffRange(".", "")), result.statsRanges)
        assertEquals(emptyMap<String, List<String>>(), result.revsByFile)
    }

    @Test
    fun `untracked files are asked for with another status flag`() {
        val hg = FakeHg(
            mapOf(
                currentRevision to ok("feature|abc123|Work in progress"),
                listOf("status", "--rev", ".", "-mardu", "--copies") to ok("? src/New.kt")
            )
        )
        val result = load(hg, HgDisplayMode.UNCOMMITTED, untracked = true)

        assertEquals(listOf("? src/New.kt"), result.files.map { "${it.status} ${it.path}" })
    }

    @Test
    fun `a merge is compared against the last merged revision of the parent branch`() {
        val mergeBase = "max(ancestors(.) and branch('default'))"
        val hg = FakeHg(
            mapOf(
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(mergeBase) to ok("default|def456|Parent work"),
                status(mergeBase) to ok("M src/A.kt")
            )
        )
        val result = load(hg, HgDisplayMode.MERGE, customBranch = "default")

        assertEquals(
            listOf(currentRevision, baseRevision(mergeBase), status(mergeBase)),
            hg.calls
        )
        assertEquals(mergeBase, result.targetRev)
        assertEquals(
            "Branch: feature (abc123) \"Work in progress\" vs Last merged in: default (def456) \"Parent work\"",
            result.statusText
        )
    }

    @Test
    fun `the base mode keeps only the files the branch itself touched`() {
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("feature"),
                branchRevisions to ok(revisionsLog),
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(branchPoint) to ok("default|def456|Parent work"),
                listOf("log", "-r", "n1+n2", "--template", "{join(files, '\\n')}\\n") to ok("src/A.kt"),
                status(branchPoint) to ok("M src/A.kt\nM src/Other.kt")
            )
        )
        val result = load(hg, HgDisplayMode.BRANCH)

        assertEquals(
            listOf(
                currentBranch,
                branchRevisions,
                currentRevision,
                baseRevision(branchPoint),
                listOf("log", "-r", "n1+n2", "--template", "{join(files, '\\n')}\\n"),
                status(branchPoint)
            ),
            hg.calls
        )
        assertEquals(listOf("M src/A.kt"), result.files.map { "${it.status} ${it.path}" })
        assertEquals("feature", result.branch)
        assertEquals(listOf("n1", "n2"), result.revisions.map { it.node })
        assertEquals(mapOf(DiffRange(branchPoint, "") to listOf("src/A.kt")), result.statsPaths)
    }

    @Test
    fun `an unselected revision is left out of the compared range`() {
        val onlySecond = "p1(min(n2))"
        val asked = ArrayList<String>()
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("feature"),
                branchRevisions to ok(revisionsLog),
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(onlySecond) to ok("default|def456|Parent work"),
                listOf("log", "-r", "n2", "--template", "{join(files, '\\n')}\\n") to ok("src/A.kt"),
                status(onlySecond) to ok("M src/A.kt")
            )
        )
        val result = load(hg, HgDisplayMode.BRANCH, overrides = { branch ->
            asked.add(branch)
            listOf("n1=0")
        })

        assertEquals(listOf("feature"), asked)
        assertEquals(onlySecond, result.targetRev)
        assertNull(result.error)
    }

    @Test
    fun `a branch without revisions of its own is reported instead of loaded`() {
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("feature"),
                branchRevisions to ok(""),
                currentRevision to ok("feature|abc123|Work in progress")
            )
        )
        val result = load(hg, HgDisplayMode.CHANGESETS)

        assertEquals(listOf(currentBranch, branchRevisions, currentRevision), hg.calls)
        assertTrue(result.files.isEmpty())
        assertEquals("No revisions of this branch found. Use 'Uncommitted'.", result.error)
    }

    @Test
    fun `changesets of one revision each are read from a single prefetch log`() {
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("feature"),
                branchRevisions to ok(revisionsLog),
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(branchPoint) to ok("default|def456|Parent work"),
                changesetLog to ok("@n1\nsrc/A.kt\n@n2\nsrc/B.kt"),
                prefetchLog to ok("@n1\nM src/A.kt\n@n2\nA src/B.kt")
            )
        )
        val result = load(hg, HgDisplayMode.CHANGESETS)

        assertEquals(
            listOf(
                currentBranch,
                branchRevisions,
                currentRevision,
                baseRevision(branchPoint),
                changesetLog,
                prefetchLog
            ),
            hg.calls
        )
        assertEquals(
            listOf("M src/A.kt p1(n1)..n1", "A src/B.kt p1(n2)..n2"),
            result.files.map { "${it.status} ${it.path} ${it.baseRev}..${it.headRev}" }
        )
        assertEquals(
            listOf(DiffRange("p1(n1)", "n1"), DiffRange("p1(n2)", "n2")),
            result.statsRanges
        )
        assertEquals(mapOf("src/a.kt" to listOf("n1"), "src/b.kt" to listOf("n2")), result.revsByFile)
    }

    @Test
    fun `a file touched twice is read as one range with a status call`() {
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("feature"),
                branchRevisions to ok(revisionsLog),
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(branchPoint) to ok("default|def456|Parent work"),
                changesetLog to ok("@n1\nsrc/A.kt\n@n2\nsrc/A.kt\nsrc/B.kt"),
                status("p1(n1)", "n2") to ok("M src/A.kt"),
                status("p1(n2)", "n2") to ok("A src/B.kt")
            )
        )
        val result = load(hg, HgDisplayMode.CHANGESETS)

        assertEquals(
            listOf(
                currentBranch,
                branchRevisions,
                currentRevision,
                baseRevision(branchPoint),
                changesetLog,
                status("p1(n1)", "n2"),
                status("p1(n2)", "n2")
            ),
            hg.calls
        )
        assertEquals(
            listOf("M src/A.kt p1(n1)..n2", "A src/B.kt p1(n2)..n2"),
            result.files.map { "${it.status} ${it.path} ${it.baseRev}..${it.headRev}" }
        )
        assertEquals(mapOf("src/a.kt" to listOf("n1", "n2"), "src/b.kt" to listOf("n2")), result.revsByFile)
    }

    @Test
    fun `a renamed file joins the ranges of both its names`() {
        val renameStatus = "A src/New.kt\n  src/Old.kt\nR src/Old.kt"
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("feature"),
                branchRevisions to ok(revisionsLog),
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(branchPoint) to ok("default|def456|Parent work"),
                changesetLog to ok("@n1\nsrc/Old.kt\n@n2\nsrc/New.kt\nsrc/Old.kt"),
                status("p1(n1)", "n2") to ok(renameStatus),
                status("p1(n2)", "n2") to ok(renameStatus)
            )
        )
        val result = load(hg, HgDisplayMode.CHANGESETS)

        assertEquals(
            listOf(
                currentBranch,
                branchRevisions,
                currentRevision,
                baseRevision(branchPoint),
                changesetLog,
                status("p1(n1)", "n2"),
                status("p1(n2)", "n2")
            ),
            hg.calls
        )
        assertEquals(listOf("→ src/New.kt p1(n1)..n2"), result.files.map {
            "${it.status} ${it.path} ${it.baseRev}..${it.headRev}"
        })
        assertEquals(listOf(DiffRange("p1(n1)", "n2")), result.statsRanges)
        assertEquals(
            mapOf("src/old.kt" to listOf("n1", "n2"), "src/new.kt" to listOf("n1", "n2")),
            result.revsByFile
        )
    }

    @Test
    fun `a rename beside a plain deletion keeps every touched path in the result`() {
        val prefetched = "@n1\n" +
            "M src/Kept.kt\n" +
            "A src/New.kt\n" +
            "R src/Gone.kt\n" +
            "R src/Old.kt\n" +
            "* src/New.kt\n" +
            "= src/Old.kt\n" +
            "@n2\n" +
            "M src/New.kt\n" +
            "R src/Extra.kt"
        val spanStatus = "A src/New.kt\n  src/Old.kt\n" +
            "M src/Kept.kt\nR src/Extra.kt\nR src/Gone.kt\nR src/Old.kt"
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("feature"),
                branchRevisions to ok(revisionsLog),
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(branchPoint) to ok("default|def456|Parent work"),
                changesetLog to ok("@n1\nsrc/New.kt\nsrc/Old.kt\nsrc/Gone.kt\nsrc/Kept.kt\n@n2\nsrc/New.kt\nsrc/Extra.kt"),
                prefetchLog to ok(prefetched),
                status("p1(n1)", "n2") to ok(spanStatus)
            )
        )
        val result = load(hg, HgDisplayMode.CHANGESETS)

        assertEquals(
            listOf(
                currentBranch,
                branchRevisions,
                currentRevision,
                baseRevision(branchPoint),
                changesetLog,
                prefetchLog,
                status("p1(n1)", "n2")
            ),
            hg.calls
        )
        assertEquals(
            listOf(
                "→ src/New.kt p1(n1)..n2",
                "M src/Kept.kt p1(n1)..n1",
                "R src/Gone.kt p1(n1)..n1",
                "R src/Extra.kt p1(n2)..n2"
            ),
            result.files.map { "${it.status} ${it.path} ${it.baseRev}..${it.headRev}" }
        )
        assertEquals(
            setOf("src/new.kt", "src/old.kt", "src/gone.kt", "src/kept.kt", "src/extra.kt"),
            result.files.flatMap { listOf(it.path, it.copiedFrom) }
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
                .toSet()
        )
    }

    @Test
    fun `a failed status of a range stops the load with the hg message`() {
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("feature"),
                branchRevisions to ok(revisionsLog),
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(branchPoint) to ok("default|def456|Parent work"),
                changesetLog to ok("@n1\nsrc/A.kt\n@n2\nsrc/A.kt\nsrc/B.kt"),
                status("p1(n1)", "n2") to failed("abort: unknown revision"),
                status("p1(n2)", "n2") to ok("A src/B.kt")
            )
        )
        val result = load(hg, HgDisplayMode.CHANGESETS)

        assertTrue(result.files.isEmpty())
        assertEquals(
            "ROOT BRANCH DETECTED (No Parent). Use 'Uncommitted'.\nDetails: abort: unknown revision",
            result.error
        )
        assertEquals(branchPoint, result.targetRev)
    }

    private val changesetLog =
        listOf("log", "-r", "n1+n2", "--template", BranchRevisions.FILES_TEMPLATE)

    private val prefetchLog =
        listOf("log", "-r", "n1+n2", "--template", ChangesetFiles.TEMPLATE)

    private fun load(
        hg: FakeHg,
        mode: HgDisplayMode,
        customBranch: String = "",
        untracked: Boolean = false,
        overrides: (String) -> List<String> = { emptyList() }
    ): ChangesResult = ChangesLoader(hg, ParallelJobs(TaskLauncher.SAME_THREAD), overrides, {})
        .load(mode, customBranch, untracked)

    private fun ok(stdout: String) = HgResult(0, stdout, "")

    private fun failed(stderr: String) = HgResult(1, "", stderr)

    private class FakeHg(private val answers: Map<List<String>, HgResult>) : HgCommands {

        val calls = ArrayList<List<String>>()

        @Synchronized
        override fun run(args: List<String>): HgResult {
            calls.add(args)
            return answers[args] ?: HgResult(1, "", "no answer prepared for $args")
        }
    }
}
