package com.narzaru.mercurial.hg

import com.narzaru.mercurial.changes.DiffStatsPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream

class HgContentHashTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun hashOf(text: String) = HgContentHash.of(ByteArrayInputStream(text.toByteArray()))

    @Test
    fun `a content hash is a prefixed hex hash of the diff hash length`() {
        val hash = hashOf("hello")

        assertTrue(hash.startsWith(HgContentHash.PREFIX))
        val hex = hash.removePrefix(HgContentHash.PREFIX)
        assertEquals(HgDiffStatParser.HASH_LENGTH, hex.length)
        assertTrue(hex.all { it in "0123456789abcdef" })
    }

    @Test
    fun `a content hash never looks like a diff hash`() {
        val hash = hashOf("diff --git a/a.kt b/a.kt\n@@ -1 +1 @@\n-old\n+new")
        val diffHash = HgDiffStatParser.parse("diff --git a/a.kt b/a.kt\n@@ -1 +1 @@\n-old\n+new".toByteArray())
            .getValue("a.kt").hash

        assertNotEquals(diffHash, hash)
        assertNotEquals(DiffStatsPlan.NO_DIFF_HASH, hash)
        assertTrue(hash.any { it !in "0123456789abcdef" })
    }

    @Test
    fun `a content hash does not break the stored mark format`() {
        assertTrue(' ' !in HgContentHash.PREFIX && ',' !in HgContentHash.PREFIX)
    }

    @Test
    fun `the same content gives the same hash`() {
        assertEquals(hashOf("text\n"), hashOf("text\n"))
    }

    @Test
    fun `different content gives a different hash`() {
        assertNotEquals(hashOf("text\n"), hashOf("text \n"))
        assertNotEquals(hashOf(""), hashOf("\n"))
    }

    @Test
    fun `content read in pieces hashes like content read at once`() {
        val bytes = ByteArray(3 * 1024 * 1024 + 7) { (it % 251).toByte() }
        val trickling = object : InputStream() {
            private var position = 0
            override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (position >= bytes.size) return -1
                val count = minOf(length, 1000, bytes.size - position)
                System.arraycopy(bytes, position, target, offset, count)
                position += count
                return count
            }
        }

        assertEquals(HgContentHash.of(ByteArrayInputStream(bytes)), HgContentHash.of(trickling))
    }

    @Test
    fun `a file is hashed by its bytes`() {
        val file = folder.newFile("a.txt")
        file.writeBytes("line\r\n".toByteArray())

        assertEquals(hashOf("line\r\n"), HgContentHash.ofFile(file))
    }

    @Test
    fun `a missing file has no hash`() {
        assertNull(HgContentHash.ofFile(folder.root.resolve("gone.txt")))
    }

    @Test
    fun `a directory has no hash`() {
        assertNull(HgContentHash.ofFile(folder.newFolder("dir")))
    }
}
