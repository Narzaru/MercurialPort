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
    fun `output without hunks changes nothing`() {
        assertEquals(emptyList<PatchHunk>(), HgPatchApplier.parseHunks(""))
    }
}
