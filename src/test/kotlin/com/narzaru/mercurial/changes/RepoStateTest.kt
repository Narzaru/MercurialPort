package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoStateTest {

    @Test
    fun `the working directory parent is read off the template`() {
        assertEquals(RepoState("default", "026135ef5636"), RepoState.parse("default|026135ef5636"))
    }

    @Test
    fun `trailing whitespace around the answer is dropped`() {
        assertEquals(RepoState("feature", "abc123"), RepoState.parse(" feature|abc123 \n"))
    }

    @Test
    fun `the longer revision template is read the same way`() {
        assertEquals(
            RepoState("default", "026135ef5636"),
            RepoState.parse("default|026135ef5636|first line of the description")
        )
    }

    @Test
    fun `an answer mercurial could not give is not a state`() {
        for (output in listOf("", "default", "Unknown|?|?", "default|")) {
            assertFalse(output, RepoState.parse(output).isKnown)
        }
    }

    @Test
    fun `the same parent on the same branch is no reason to reload`() {
        val state = RepoState("default", "abc123")

        assertFalse(RepoState.changed(state, state))
    }

    @Test
    fun `an update to another revision is a reason to reload`() {
        assertTrue(RepoState.changed(RepoState("default", "abc123"), RepoState("default", "def456")))
    }

    @Test
    fun `a branch switch at the same node is a reason to reload`() {
        assertTrue(RepoState.changed(RepoState("default", "abc123"), RepoState("feature", "abc123")))
    }

    @Test
    fun `an unknown state on either side is no reason to reload`() {
        val state = RepoState("default", "abc123")

        assertFalse(RepoState.changed(RepoState.UNKNOWN, state))
        assertFalse(RepoState.changed(state, RepoState.UNKNOWN))
    }
}
