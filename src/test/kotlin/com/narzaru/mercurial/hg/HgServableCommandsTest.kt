package com.narzaru.mercurial.hg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HgServableCommandsTest {

    @Test
    fun `queries the panels run are servable`() {
        assertTrue(HgServableCommands.isServable(listOf("status", "--rev", "a", "--rev", "b")))
        assertTrue(HgServableCommands.isServable(listOf("log", "-f", "file.txt", "--template", "{rev}")))
        assertTrue(HgServableCommands.isServable(listOf("diff", "--git", "--rev", "a", "--rev", "b")))
        assertTrue(HgServableCommands.isServable(listOf("cat", "-r", "tip", "file.bin")))
        assertTrue(HgServableCommands.isServable(listOf("debugrename", "-r", "tip", "file.txt")))
    }

    @Test
    fun `a command that writes is not servable`() {
        assertFalse(HgServableCommands.isServable(listOf("revert", "--no-backup", "file.txt")))
        assertFalse(HgServableCommands.isServable(listOf("commit", "-m", "message")))
        assertFalse(HgServableCommands.isServable(listOf("branch", "feature")))
    }

    @Test
    fun `an empty command is not servable`() {
        assertFalse(HgServableCommands.isServable(emptyList()))
    }

    @Test
    fun `a command that moves the repository away from the working directory is not servable`() {
        assertFalse(HgServableCommands.isServable(listOf("status", "-R", "D:/other")))
        assertFalse(HgServableCommands.isServable(listOf("status", "--repository", "D:/other")))
        assertFalse(HgServableCommands.isServable(listOf("log", "--cwd", "D:/other")))
    }

    @Test
    fun `an option written together with its value is not servable either`() {
        assertFalse(HgServableCommands.isServable(listOf("status", "-RD:/other")))
        assertFalse(HgServableCommands.isServable(listOf("status", "--repository=D:/other")))
        assertFalse(HgServableCommands.isServable(listOf("log", "--cwd=D:/other")))
        assertFalse(HgServableCommands.isServable(listOf("log", "--config=ui.merge=false")))
    }

    @Test
    fun `an option that writes to disk or rewrites the configuration is not servable`() {
        assertFalse(HgServableCommands.isServable(listOf("cat", "-o", "out.txt", "-r", "tip", "file.txt")))
        assertFalse(HgServableCommands.isServable(listOf("cat", "--output", "out.txt", "file.txt")))
        assertFalse(HgServableCommands.isServable(listOf("status", "--config", "extensions.evolve=")))
        assertFalse(HgServableCommands.isServable(listOf("log", "--profile")))
        assertFalse(HgServableCommands.isServable(listOf("log", "--debugger")))
    }

    @Test
    fun `an unambiguous abbreviation of a forbidden option is not servable`() {
        assertFalse(HgServableCommands.isServable(listOf("status", "--repo", "D:/other")))
        assertFalse(HgServableCommands.isServable(listOf("log", "--cw", "D:/other")))
        assertFalse(HgServableCommands.isServable(listOf("status", "--configf", "hgrc")))
        assertFalse(HgServableCommands.isServable(listOf("cat", "--outp", "out.txt")))
        assertFalse(HgServableCommands.isServable(listOf("log", "--prof")))
    }

    @Test
    fun `an option that resets the encoding of the server is not servable`() {
        assertFalse(HgServableCommands.isServable(listOf("status", "--encoding", "cp1251")))
        assertFalse(HgServableCommands.isServable(listOf("status", "--encoding=cp1251")))
        assertFalse(HgServableCommands.isServable(listOf("status", "--enc", "cp1251")))
        assertFalse(HgServableCommands.isServable(listOf("status", "--encodingmode", "replace")))
    }

    @Test
    fun `an option of the command itself is left alone`() {
        assertTrue(HgServableCommands.isServable(listOf("diff", "-U", "0", "--ignore-space-at-eol")))
        assertTrue(HgServableCommands.isServable(listOf("log", "-r", "0:5", "--template", "{rev}")))
        assertTrue(HgServableCommands.isServable(listOf("status", "--rev", "a", "--rev", "b", "-A")))
    }
}
