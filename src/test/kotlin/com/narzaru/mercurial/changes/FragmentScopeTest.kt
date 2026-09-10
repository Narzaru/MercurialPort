package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Test

class FragmentScopeTest {

    private val base = listOf("using A;", "class C", "{", "    void M()", "    {", "    }", "}")
    private val head = listOf(
        "using A;", "class C", "{", "    void M()", "    {",
        "        merged();",
        "        mine();",
        "    }", "}"
    )

    private val fragments = listOf(DiffFragment(LineRange(5, 5), LineRange(5, 7)))

    @Test
    fun `a fragment the branch touched is shown whole, merged-in lines included`() {
        val own = listOf(LineRange(6, 7))

        val left = FragmentScope.leftSide(base, head, fragments, own)

        assertEquals(base, left)
    }

    @Test
    fun `a fragment the branch never touched stays out of the diff`() {
        val left = FragmentScope.leftSide(base, head, fragments, own = listOf(LineRange(0, 1)))

        assertEquals(head, left)
    }

    @Test
    fun `only the touched fragment of several is widened`() {
        val theirBase = listOf("a", "b", "c", "d")
        val theirHead = listOf("a", "THEIRS", "c", "MINE")
        val two = listOf(
            DiffFragment(LineRange(1, 2), LineRange(1, 2)),
            DiffFragment(LineRange(3, 4), LineRange(3, 4))
        )

        val left = FragmentScope.leftSide(theirBase, theirHead, two, own = listOf(LineRange(3, 4)), gap = 0)

        assertEquals(listOf("a", "THEIRS", "c", "d"), left)
    }

    @Test
    fun `an insertion counts as touched when the branch wrote it`() {
        val left = FragmentScope.leftSide(
            listOf("a", "b"), listOf("a", "new", "b"),
            listOf(DiffFragment(LineRange(1, 1), LineRange(1, 2))),
            own = listOf(LineRange(1, 2))
        )

        assertEquals(listOf("a", "b"), left)
    }

    @Test
    fun `fragments a couple of lines apart are joined, distant ones are not`() {
        val close = listOf(
            DiffFragment(LineRange(1, 2), LineRange(1, 2)),
            DiffFragment(LineRange(4, 5), LineRange(4, 5))
        )
        assertEquals(listOf(DiffFragment(LineRange(1, 5), LineRange(1, 5))), FragmentScope.join(close))

        val far = listOf(
            DiffFragment(LineRange(1, 2), LineRange(1, 2)),
            DiffFragment(LineRange(5, 6), LineRange(5, 6))
        )
        assertEquals(far, FragmentScope.join(far))
    }

    @Test
    fun `a merged-in line next to the branch's own is widened along with it`() {
        val theirBase = listOf("a", "b", "c", "d")
        val theirHead = listOf("a", "THEIRS", "c", "MINE")

        val left = FragmentScope.leftSide(
            theirBase, theirHead,
            listOf(
                DiffFragment(LineRange(1, 2), LineRange(1, 2)),
                DiffFragment(LineRange(3, 4), LineRange(3, 4))
            ),
            own = listOf(LineRange(3, 4))
        )

        assertEquals(theirBase, left)
    }

    @Test
    fun `without fragments the head is returned as it is`() {
        assertEquals(head, FragmentScope.leftSide(base, head, emptyList(), listOf(LineRange(0, 1))))
    }
}
