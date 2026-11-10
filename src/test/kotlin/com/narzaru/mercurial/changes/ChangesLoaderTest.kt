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

    private val branchPoint = "p1(n1)"

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
        assertEquals(listOf("M src/A.kt", "A src/B.kt"), result.files.map { "${it.status.letter} ${it.path}" })
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

        assertEquals(listOf("? src/New.kt"), result.files.map { "${it.status.letter} ${it.path}" })
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
                listOf("log", "-r", SelectionRevsets.OWN_COMMITS,"--template", "{join(files, '\\n')}\\n") to ok("src/A.kt"),
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
                listOf("log", "-r", SelectionRevsets.OWN_COMMITS,"--template", "{join(files, '\\n')}\\n"),
                status(branchPoint)
            ),
            hg.calls
        )
        assertEquals(listOf("M src/A.kt"), result.files.map { "${it.status.letter} ${it.path}" })
        assertEquals("feature", result.branch)
        assertEquals(listOf("n1", "n2"), result.revisions.map { it.node })
        assertEquals(mapOf(DiffRange(branchPoint, "") to listOf("src/A.kt")), result.statsPaths)
    }

    @Test
    fun `the base mode of a branch whose own revisions touched no files lists nothing`() {
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("feature"),
                branchRevisions to ok(revisionsLog),
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(branchPoint) to ok("default|def456|Parent work"),
                listOf("log", "-r", SelectionRevsets.OWN_COMMITS,"--template", "{join(files, '\\n')}\\n") to ok("\n\n"),
                status(branchPoint) to ok("M src/Merged.kt\nA src/AlsoMerged.kt")
            )
        )
        val result = load(hg, HgDisplayMode.BRANCH)

        assertNull(result.error)
        assertEquals(emptyList<String>(), result.files.map { it.path })
    }

    @Test
    fun `the base mode lists every file when the own files cannot be read`() {
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("feature"),
                branchRevisions to ok(revisionsLog),
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(branchPoint) to ok("default|def456|Parent work"),
                listOf("log", "-r", SelectionRevsets.OWN_COMMITS,"--template", "{join(files, '\\n')}\\n") to failed("abort: boom"),
                status(branchPoint) to ok("M src/Merged.kt")
            )
        )
        val result = load(hg, HgDisplayMode.BRANCH)

        assertEquals(listOf("src/Merged.kt"), result.files.map { it.path })
        assertEquals(emptyMap<DiffRange, List<String>>(), result.statsPaths)
    }

    @Test
    fun `an unselected revision is left out of the compared range`() {
        val onlySecond = "p1(n2)"
        val asked = ArrayList<String>()
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("feature"),
                branchRevisions to ok(revisionsLog),
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(onlySecond) to ok("default|def456|Parent work"),
                listOf(
                    "log", "-r", "(${SelectionRevsets.OWN_COMMITS}) - (n1)", "--template", "{join(files, '\\n')}\\n"
                ) to ok("src/A.kt"),
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
    fun `a failed branch query leaves the branch unknown and the overrides unread`() {
        val asked = ArrayList<String>()
        val hg = FakeHg(
            mapOf(
                currentBranch to failed("abort: no repository found"),
                branchRevisions to ok(revisionsLog),
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(branchPoint) to ok("default|def456|Parent work"),
                listOf("log", "-r", SelectionRevsets.OWN_COMMITS,"--template", "{join(files, '\\n')}\\n") to ok("src/A.kt"),
                status(branchPoint) to ok("M src/A.kt")
            )
        )
        val result = load(hg, HgDisplayMode.BRANCH, overrides = { branch ->
            asked.add(branch)
            listOf("n1=0")
        })

        assertEquals(emptyList<String>(), asked)
        assertEquals("", result.branch)
        assertEquals(listOf("n1", "n2"), result.selection.selectedNodes(result.revisions))
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
                changesetLog to ok("@n1\n>src/A.kt\n@n2\n>src/B.kt"),
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
            result.files.map { "${it.status.letter} ${it.path} ${it.baseRev}..${it.headRev}" }
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
                changesetLog to ok("@n1\n>src/A.kt\n@n2\n>src/A.kt\n>src/B.kt"),
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
            result.files.map { "${it.status.letter} ${it.path} ${it.baseRev}..${it.headRev}" }
        )
        assertEquals(mapOf("src/a.kt" to listOf("n1", "n2"), "src/b.kt" to listOf("n2")), result.revsByFile)
    }

    @Test
    fun `a range whose files cancelled out is not counted`() {
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("feature"),
                branchRevisions to ok(revisionsLog),
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(branchPoint) to ok("default|def456|Parent work"),
                changesetLog to ok("@n1\n>src/A.kt\n@n2\n>src/A.kt\n>src/B.kt"),
                status("p1(n1)", "n2") to ok(""),
                status("p1(n2)", "n2") to ok("R src/A.kt\nA src/B.kt")
            )
        )
        val result = load(hg, HgDisplayMode.CHANGESETS)

        assertEquals(listOf("A src/B.kt p1(n2)..n2"), result.files.map {
            "${it.status.letter} ${it.path} ${it.baseRev}..${it.headRev}"
        })
        assertEquals(listOf(DiffRange("p1(n2)", "n2")), result.statsRanges)
        assertEquals(mapOf(DiffRange("p1(n2)", "n2") to listOf("src/B.kt")), result.statsPaths)
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
                changesetLog to ok("@n1\n>src/Old.kt\n@n2\n>src/New.kt\n>src/Old.kt"),
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
            "${it.status.letter} ${it.path} ${it.baseRev}..${it.headRev}"
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
                changesetLog to ok(
                    "@n1\n>src/New.kt\n>src/Old.kt\n>src/Gone.kt\n>src/Kept.kt\n@n2\n>src/New.kt\n>src/Extra.kt"
                ),
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
            result.files.map { "${it.status.letter} ${it.path} ${it.baseRev}..${it.headRev}" }
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
                changesetLog to ok("@n1\n>src/A.kt\n@n2\n>src/A.kt\n>src/B.kt"),
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

    private val longLog = (1..30).joinToString("\n") { "$it|node$it|-1|Bob|2024-01-01 10:00 +0000|Work $it" }

    @Test
    fun `the base mode of a long branch names its revisions by a revset`() {
        val ownFiles = listOf("log", "-r", SelectionRevsets.OWN_COMMITS, "--template", "{join(files, '\\n')}\\n")
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("default"),
                branchRevisions to ok(longLog),
                currentRevision to ok("default|abc123|Work 30"),
                baseRevision("p1(node1)") to ok("|000000|"),
                ownFiles to ok("src/A.kt"),
                status("p1(node1)") to ok("M src/A.kt")
            )
        )
        val result = load(hg, HgDisplayMode.BRANCH)

        assertEquals(
            listOf(
                currentBranch, branchRevisions, currentRevision,
                baseRevision("p1(node1)"), ownFiles, status("p1(node1)")
            ),
            hg.calls
        )
        assertNull(result.error)
        assertEquals(listOf("M src/A.kt"), result.files.map { "${it.status.letter} ${it.path}" })
    }

    @Test
    fun `the changesets of a long branch are read by a revset without the deselected ones`() {
        val selected = "(${SelectionRevsets.OWN_COMMITS}) - (node5)"
        val log = listOf("log", "-r", selected, "--template", BranchRevisions.FILES_TEMPLATE)
        val prefetch = listOf("log", "-r", "($selected) - (node1)", "--template", ChangesetFiles.TEMPLATE)
        val singles = (3..30).filter { it != 5 }
        val touches = "@node1\n>src/F1.kt\n@node2\n>src/F1.kt\n>src/F2.kt\n" +
            singles.joinToString("\n") { "@node$it\n>src/F$it.kt" } +
            "\n@node99\n>src/Late.kt"
        val prefetched = "@node2\nM src/F2.kt\n" + singles.joinToString("\n") { "@node$it\nM src/F$it.kt" }
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("default"),
                branchRevisions to ok(longLog),
                currentRevision to ok("default|abc123|Work 30"),
                baseRevision("p1(node1)") to ok("|000000|"),
                log to ok(touches),
                prefetch to ok(prefetched),
                status("p1(node1)", "node2") to ok("M src/F1.kt")
            )
        )
        val result = load(hg, HgDisplayMode.CHANGESETS, overrides = { listOf("node5=0") })

        assertNull(result.error)
        assertEquals(
            listOf(
                currentBranch, branchRevisions, currentRevision, baseRevision("p1(node1)"),
                log, prefetch, status("p1(node1)", "node2")
            ),
            hg.calls
        )
        assertEquals(29, result.files.size)
        assertTrue(result.files.none { it.path == "src/Late.kt" || it.path == "src/F5.kt" })
    }

    private fun limitedRevisions(limit: Int) = listOf(
        "log", "-r", "reverse(${BranchRevisions.OWN_REVS})", "-l", "${limit + 1}",
        "--template", BranchRevisions.TEMPLATE + "\\n"
    )

    private fun newestFirst(log: String) = log.lines().asReversed().joinToString("\n")

    private fun assertSameLoad(expected: ChangesResult, actual: ChangesResult) {
        assertEquals(expected.revisions, actual.revisions)
        assertEquals(expected.selection.selectedNodes(expected.revisions), actual.selection.selectedNodes(actual.revisions))
        assertEquals(expected.targetRev, actual.targetRev)
        assertEquals(expected.statusText, actual.statusText)
        assertEquals(expected.error, actual.error)
        assertEquals(expected.files, actual.files)
        assertEquals(expected.statsRanges, actual.statsRanges)
        assertEquals(expected.statsPaths, actual.statsPaths)
        assertEquals(expected.revsByFile, actual.revsByFile)
        assertNull(actual.revisionsMoreThan)
    }

    @Test
    fun `changesets of a branch within the limit load exactly as without one`() {
        val answers = mapOf(
            currentBranch to ok("feature"),
            currentRevision to ok("feature|abc123|Work in progress"),
            baseRevision(branchPoint) to ok("default|def456|Parent work"),
            changesetLog to ok("@n1\n>src/A.kt\n@n2\n>src/A.kt\n>src/B.kt"),
            status("p1(n1)", "n2") to ok("M src/A.kt"),
            status("p1(n2)", "n2") to ok("A src/B.kt")
        )
        val unlimited = FakeHg(answers + (branchRevisions to ok(revisionsLog)))
        val limited = FakeHg(answers + (limitedRevisions(2) to ok(newestFirst(revisionsLog))))

        val expected = load(unlimited, HgDisplayMode.CHANGESETS)
        val actual = load(limited, HgDisplayMode.CHANGESETS, limit = 2)

        assertEquals(unlimited.calls.map { if (it == branchRevisions) limitedRevisions(2) else it }, limited.calls)
        assertSameLoad(expected, actual)
        assertEquals(listOf("n1", "n2"), actual.revisions.map { it.node })
    }

    @Test
    fun `the base mode of a branch within the limit keeps its overrides and branch point`() {
        val selected = "(${SelectionRevsets.OWN_COMMITS}) - (node1)"
        val ownFiles = listOf("log", "-r", selected, "--template", "{join(files, '\\n')}\\n")
        val answers = mapOf(
            currentBranch to ok("default"),
            currentRevision to ok("default|abc123|Work 30"),
            baseRevision("p1(node2)") to ok("|000000|"),
            ownFiles to ok("src/A.kt"),
            status("p1(node2)") to ok("M src/A.kt")
        )
        val overrides = { _: String -> listOf("node1=0") }
        val unlimited = FakeHg(answers + (branchRevisions to ok(longLog)))
        val limited = FakeHg(answers + (limitedRevisions(30) to ok(newestFirst(longLog))))

        val expected = load(unlimited, HgDisplayMode.BRANCH, overrides = overrides)
        val actual = load(limited, HgDisplayMode.BRANCH, overrides = overrides, limit = 30)

        assertNull(actual.error)
        assertEquals("p1(node2)", actual.targetRev)
        assertSameLoad(expected, actual)
    }

    @Test
    fun `changesets of a branch over the limit are refused before any further hg call`() {
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("default"),
                limitedRevisions(1) to ok(newestFirst(revisionsLog)),
                currentRevision to ok("default|abc123|Work in progress")
            )
        )
        val result = load(hg, HgDisplayMode.CHANGESETS, limit = 1)

        assertEquals(listOf(currentBranch, limitedRevisions(1), currentRevision), hg.calls)
        assertEquals(
            "Branch 'default' has more than 1 revisions, too many for 'Changesets'. " +
                "Use 'Uncommitted' or 'Merge', or raise the limit in Settings → Tools → Mercurial Port.",
            result.error
        )
        assertTrue(result.files.isEmpty())
        assertTrue(result.revisions.isEmpty())
        assertEquals("default", result.branch)
        assertEquals(1, result.revisionsMoreThan)
        assertEquals("abc123", result.currentInfo.split('|')[1])
    }

    @Test
    fun `the base mode of a branch over the limit is refused before any further hg call`() {
        val hg = FakeHg(
            mapOf(
                currentBranch to ok("default"),
                limitedRevisions(29) to ok(newestFirst(longLog)),
                currentRevision to ok("default|abc123|Work 30")
            )
        )
        val result = load(hg, HgDisplayMode.BRANCH, limit = 29)

        assertEquals(listOf(currentBranch, limitedRevisions(29), currentRevision), hg.calls)
        assertTrue(result.error!!.startsWith("Branch 'default' has more than 29 revisions, too many for 'Base'."))
        assertEquals(29, result.revisionsMoreThan)
    }

    @Test
    fun `the limit leaves the merge mode alone`() {
        val mergeBase = "max(ancestors(.) and branch('default'))"
        val hg = FakeHg(
            mapOf(
                currentRevision to ok("feature|abc123|Work in progress"),
                baseRevision(mergeBase) to ok("default|def456|Parent work"),
                status(mergeBase) to ok("M src/A.kt")
            )
        )
        val result = load(hg, HgDisplayMode.MERGE, customBranch = "default", limit = 1)

        assertEquals(listOf(currentRevision, baseRevision(mergeBase), status(mergeBase)), hg.calls)
        assertNull(result.error)
        assertNull(result.revisionsMoreThan)
    }

    private val changesetLog =
        listOf("log", "-r", SelectionRevsets.OWN_COMMITS,"--template", BranchRevisions.FILES_TEMPLATE)

    private val prefetchLog =
        listOf("log", "-r", SelectionRevsets.OWN_COMMITS,"--template", ChangesetFiles.TEMPLATE)

    private fun load(
        hg: FakeHg,
        mode: HgDisplayMode,
        customBranch: String = "",
        untracked: Boolean = false,
        overrides: (String) -> List<String> = { emptyList() },
        limit: Int = 0
    ): ChangesResult = ChangesLoader(hg, ParallelJobs(SameThreadLauncher), BranchSizeGuard(limit), overrides, {})
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
