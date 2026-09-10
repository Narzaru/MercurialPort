package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HgDiffStatParserTest {

    private fun parse(diff: String) = HgDiffStatParser.parse(diff.toByteArray(Charsets.UTF_8))

    @Test
    fun `counts added and removed lines`() {
        val stats = parse(
            """
            diff --git a/src/main.kt b/src/main.kt
            --- a/src/main.kt
            +++ b/src/main.kt
            @@ -1,3 +1,3 @@
             unchanged
            -old line
            +new line
            +another new
            """.trimIndent()
        )

        assertEquals(2, stats.getValue("src/main.kt").added)
        assertEquals(1, stats.getValue("src/main.kt").removed)
    }

    @Test
    fun `file headers are not counted`() {
        val stats = parse(
            """
            diff --git a/a.kt b/a.kt
            --- a/a.kt
            +++ b/a.kt
            @@ -0,0 +1 @@
            +one
            """.trimIndent()
        )

        assertEquals(1, stats.getValue("a.kt").added)
        assertEquals(0, stats.getValue("a.kt").removed)
    }

    @Test
    fun `content lines that look like file headers are counted`() {
        val stats = parse(
            """
            diff --git a/a.sql b/a.sql
            --- a/a.sql
            +++ b/a.sql
            @@ -1,2 +1,2 @@
            --- comment
            +++ replacement
            """.trimIndent()
        )

        assertEquals(1, stats.getValue("a.sql").added)
        assertEquals(1, stats.getValue("a.sql").removed)
    }

    @Test
    fun `binary patch contributes nothing`() {
        val stats = parse(
            """
            diff --git a/logo.png b/logo.png
            index 0000000..1111111
            GIT binary patch
            literal 120
            zcmeAS@N?(olHy`uVBq!ia0vp^AH+H-
            +cmV-2000
            -zcmZQz

            literal 0
            HcmV?d00001
            """.trimIndent()
        )

        assertEquals(0, stats.getValue("logo.png").added)
        assertEquals(0, stats.getValue("logo.png").removed)
    }

    @Test
    fun `a binary section does not spill into the next file`() {
        val stats = parse(
            """
            diff --git a/logo.png b/logo.png
            GIT binary patch
            +cmV-2000
            diff --git a/a.kt b/a.kt
            --- a/a.kt
            +++ b/a.kt
            @@ -1,1 +1,1 @@
            +one
            """.trimIndent()
        )

        assertEquals(0, stats.getValue("logo.png").added)
        assertEquals(1, stats.getValue("a.kt").added)
    }

    @Test
    fun `deleting a whole file counts every line as removed`() {
        val stats = parse(
            """
            diff --git a/src/Gone.kt b/src/Gone.kt
            deleted file mode 100644
            --- a/src/Gone.kt
            +++ /dev/null
            @@ -1,3 +0,0 @@
            -one
            -two
            -three
            """.trimIndent()
        )

        assertEquals(setOf("src/Gone.kt"), stats.keys)
        assertEquals(3, stats.getValue("src/Gone.kt").removed)
        assertEquals(0, stats.getValue("src/Gone.kt").added)
    }

    @Test
    fun `adding a whole file counts every line as added`() {
        val stats = parse(
            """
            diff --git a/src/New.kt b/src/New.kt
            new file mode 100644
            --- /dev/null
            +++ b/src/New.kt
            @@ -0,0 +1,2 @@
            +one
            +two
            """.trimIndent()
        )

        assertEquals(2, stats.getValue("src/New.kt").added)
        assertEquals(0, stats.getValue("src/New.kt").removed)
    }

    @Test
    fun `statistics are kept per file`() {
        val stats = parse(
            """
            diff --git a/a.kt b/a.kt
            +one
            diff --git a/b.kt b/b.kt
            -two
            -three
            """.trimIndent()
        )

        assertEquals(2, stats.size)
        assertEquals(1, stats.getValue("a.kt").added)
        assertEquals(2, stats.getValue("b.kt").removed)
        assertEquals(0, stats.getValue("b.kt").added)
    }

    @Test
    fun `CRLF output is understood`() {
        val stats = parse("diff --git a/a.kt b/a.kt\r\n+one\r\n-two\r\n")

        assertEquals(1, stats.getValue("a.kt").added)
        assertEquals(1, stats.getValue("a.kt").removed)
    }

    @Test
    fun `the path is taken from the part after space b slash`() {
        val stats = parse("diff --git a/dir/sub/file.kt b/dir/sub/file.kt\n+x")

        assertEquals(setOf("dir/sub/file.kt"), stats.keys)
    }

    @Test
    fun `a file missing from the diff has no statistics`() {
        val stats = parse("diff --git a/a.kt b/a.kt\n+x")

        assertNull(stats["b.kt"])
    }

    @Test
    fun `a rename is counted under the new path`() {
        val stats = parse(
            """
            diff --git a/src/OldName.cs b/src/NewName.cs
            rename from src/OldName.cs
            rename to src/NewName.cs
            --- a/src/OldName.cs
            +++ b/src/NewName.cs
            @@ -1,2 +1,2 @@
            -old
            +new
            """.trimIndent()
        )

        assertEquals(setOf("src/NewName.cs"), stats.keys)
        assertEquals(1, stats.getValue("src/NewName.cs").added)
        assertEquals(1, stats.getValue("src/NewName.cs").removed)
    }

    @Test
    fun `the new path comes from rename to even with a space in the name`() {
        val stats = parse(
            """
            diff --git a/src/Old Name.cs b/src/New Name.cs
            rename from src/Old Name.cs
            rename to src/New Name.cs
            --- a/src/Old Name.cs
            +++ b/src/New Name.cs
            @@ -1,1 +1,1 @@
            +new
            """.trimIndent()
        )

        assertEquals(setOf("src/New Name.cs"), stats.keys)
    }

    @Test
    fun `a copy is counted under the destination path`() {
        val stats = parse(
            """
            diff --git a/src/Source.cs b/src/Copy.cs
            copy from src/Source.cs
            copy to src/Copy.cs
            --- a/src/Source.cs
            +++ b/src/Copy.cs
            @@ -1,1 +1,2 @@
            +added
            """.trimIndent()
        )

        assertEquals(setOf("src/Copy.cs"), stats.keys)
        assertEquals(1, stats.getValue("src/Copy.cs").added)
    }

    @Test
    fun `empty output gives an empty map`() {
        assertTrue(parse("").isEmpty())
    }

    @Test
    fun `a non-ASCII path is decoded`() {
        val stats = parse("diff --git a/Модуль/файл.kt b/Модуль/файл.kt\n+x")

        assertEquals(setOf("Модуль/файл.kt"), stats.keys)
    }
}
