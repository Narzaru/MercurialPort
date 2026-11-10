package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Test

class HgPatchApplierTest {

    private val patch = """
        diff --git a/src/A.cs b/src/A.cs
        --- a/src/A.cs
        +++ b/src/A.cs
        @@ -1,4 +1,5 @@
         first
         second
        -old third
        +new third
        +extra
         fourth
    """.trimIndent()

    @Test
    fun `hunk is read with its context counted on both sides`() {
        val hunks = HgPatchApplier.parseHunks(patch)

        assertEquals(1, hunks.size)
        assertEquals(1, hunks[0].oldStart)
        assertEquals(listOf("first", "second", "old third", "fourth"), hunks[0].oldLines)
        assertEquals(listOf("first", "second", "new third", "extra", "fourth"), hunks[0].newLines)
        assertEquals(2, hunks[0].leading)
        assertEquals(1, hunks[0].trailing)
    }

    @Test
    fun `a patch rolls back out of the file it was made against`() {
        val head = listOf("first", "second", "new third", "extra", "fourth")

        val rolled = HgPatchApplier.applyReverse(head, HgPatchApplier.parseHunks(patch))

        assertEquals(0, rolled.skipped)
        assertEquals(listOf("first", "second", "old third", "fourth"), rolled.lines)
    }

    @Test
    fun `rolling back leaves lines the patch never touched`() {
        val head = listOf("merged in", "first", "second", "new third", "extra", "fourth")

        val rolled = HgPatchApplier.applyReverse(head, HgPatchApplier.parseHunks(patch))

        assertEquals(listOf("merged in", "first", "second", "old third", "fourth"), rolled.lines)
    }

    @Test
    fun `a hunk that cannot be rolled back is counted, not fatal`() {
        val head = listOf("first", "second", "someone else rewrote this", "fourth")

        val rolled = HgPatchApplier.applyReverse(head, HgPatchApplier.parseHunks(patch))

        assertEquals(1, rolled.skipped)
        assertEquals(head, rolled.lines)
    }

    @Test
    fun `byte order mark does not stop a patch and does not leak into the text`() {
        val bom = "﻿"
        val withBom = """
            @@ -1,2 +1,2 @@
            -${bom}using JetBrains.Annotations;
            +${bom}using System.Collections.Generic;
             using Nanosoft.Acad;
        """.trimIndent()
        val head = listOf("using System.Collections.Generic;", "using Nanosoft.Acad;")

        val rolled = HgPatchApplier.applyReverse(head, HgPatchApplier.parseHunks(withBom))

        assertEquals(0, rolled.skipped)
        assertEquals(listOf("using JetBrains.Annotations;", "using Nanosoft.Acad;"), rolled.lines)
    }

    @Test
    fun `hunks of another file in the same patch are not read`() {
        val twoFiles = """
            diff --git a/src/A.cs b/src/A.cs
            --- a/src/A.cs
            +++ b/src/A.cs
            @@ -1,3 +1,3 @@
             a1
            -a2
            +a2-new
             a3
            diff --git a/src/B.cs b/src/B.cs
            --- a/src/B.cs
            +++ b/src/B.cs
            @@ -1,3 +1,3 @@
             b1
            -b2
            +b2-new
             b3
        """.trimIndent()

        val ofB = HgPatchApplier.parseHunks(twoFiles, listOf("src/B.cs"))

        assertEquals(1, ofB.size)
        assertEquals(listOf("b1", "b2", "b3"), ofB[0].oldLines)
        assertEquals(1, HgPatchApplier.parseHunks(twoFiles, listOf("src/A.cs")).size)
        assertEquals(emptyList<PatchHunk>(), HgPatchApplier.parseHunks(twoFiles, listOf("src/C.cs")))
    }

    @Test
    fun `rollback lands on the fragment the patch changed, not on a lookalike above it`() {
        val head = ArrayList<String>()
        head.add("header")
        repeat(20) { head.add("inserted$it") }
        repeat(6) { head.add("filler$it") }
        head.addAll(listOf("dup1", "dup2", "new", "dup3"))
        repeat(16) { head.add("tail$it") }
        head.addAll(listOf("dup1", "dup2", "new", "dup3"))

        val inserting = StringBuilder("@@ -1,1 +1,21 @@\n header\n")
        repeat(20) { inserting.append("+inserted$it\n") }
        inserting.append("@@ -28,4 +48,4 @@\n dup1\n dup2\n-old\n+new\n dup3")

        val rolled = HgPatchApplier.applyReverse(head, HgPatchApplier.parseHunks(inserting.toString()))

        assertEquals(0, rolled.skipped)
        assertEquals(listOf(30), rolled.lines.mapIndexedNotNull { i, l -> (i + 1).takeIf { l == "old" } })
        assertEquals("new", rolled.lines[9])
    }

    @Test
    fun `content lines that look like file headers stay in the hunk`() {
        val dashes = """
            diff --git a/a.sql b/a.sql
            --- a/a.sql
            +++ b/a.sql
            @@ -1,4 +1,2 @@
             keep
            --- x
            ----
            ---
            +++i;
        """.trimIndent()

        val hunks = HgPatchApplier.parseHunks(dashes)

        assertEquals(1, hunks.size)
        assertEquals(listOf("keep", "-- x", "---", "--"), hunks[0].oldLines)
        assertEquals(listOf("keep", "++i;"), hunks[0].newLines)
    }

    private val copyNextToItsSource = """
        diff --git a/a.txt b/a.txt
        --- a/a.txt
        +++ b/a.txt
        @@ -1,1 +1,1 @@
        -a old
        +a new
        diff --git a/a.txt b/b.txt
        copy from a.txt
        copy to b.txt
        --- a/a.txt
        +++ b/b.txt
        @@ -1,1 +1,1 @@
        -a old
        +b new
    """.trimIndent()

    @Test
    fun `a copy target takes its own section, not the one of its source`() {
        val ofB = HgPatchApplier.parseHunks(copyNextToItsSource, listOf("b.txt", "a.txt"))

        assertEquals(listOf("b new"), ofB.single().newLines)
    }

    @Test
    fun `a copy source keeps its own section`() {
        val ofA = HgPatchApplier.parseHunks(copyNextToItsSource, listOf("a.txt"))

        assertEquals(listOf("a new"), ofA.single().newLines)
    }

    @Test
    fun `before the copy the target is followed under the name of its source`() {
        val beforeCopy = """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1,1 +1,1 @@
            -a older
            +a old
        """.trimIndent()

        val hunks = HgPatchApplier.parseHunks(beforeCopy, listOf("b.txt", "a.txt"))

        assertEquals(listOf("a old"), hunks.single().newLines)
    }

    @Test
    fun `a path containing a b-slash is found`() {
        val spaced = """
            diff --git a/Plan b/x.kt b/Plan b/x.kt
            --- a/Plan b/x.kt
            +++ b/Plan b/x.kt
            @@ -1,1 +1,1 @@
            -old
            +new
        """.trimIndent()

        assertEquals(1, HgPatchApplier.parseHunks(spaced, listOf("Plan b/x.kt")).size)
    }

    @Test
    fun `a renamed path containing a b-slash is found by its rename lines`() {
        val renamed = """
            diff --git a/Plan b/old.kt b/Plan b/new.kt
            rename from Plan b/old.kt
            rename to Plan b/new.kt
            --- a/Plan b/old.kt
            +++ b/Plan b/new.kt
            @@ -1,1 +1,1 @@
            -old
            +new
            diff --git a/Plan b/new.kt b/Plan b/new.kt.orig
            copy from Plan b/new.kt
            copy to Plan b/new.kt.orig
        """.trimIndent()

        val hunks = HgPatchApplier.parseHunks(renamed, listOf("Plan b/new.kt"))

        assertEquals(listOf("new"), hunks.single().newLines)
    }

    @Test
    fun `a file emptied by the patch gets its lines back`() {
        val emptied = """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1,2 +0,0 @@
            -first
            -second
        """.trimIndent()

        val rolled = HgPatchApplier.applyReverse(listOf(""), HgPatchApplier.parseHunks(emptied))

        assertEquals(0, rolled.skipped)
        assertEquals(listOf("first", "second", ""), rolled.lines)
    }

    @Test
    fun `lines deleted without context come back after the line before them`() {
        val deleted = "@@ -2,2 +1,0 @@\n-second\n-third"

        val rolled = HgPatchApplier.applyReverse(
            listOf("first", "fourth"),
            HgPatchApplier.parseHunks(deleted)
        )

        assertEquals(0, rolled.skipped)
        assertEquals(listOf("first", "second", "third", "fourth"), rolled.lines)
    }

    @Test
    fun `output without hunks changes nothing`() {
        assertEquals(emptyList<PatchHunk>(), HgPatchApplier.parseHunks(""))
    }
}
