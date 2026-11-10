package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.ChangesetsFragments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HgSettingsTest {

    @Test
    fun `defaults are served without a running application`() {
        assertEquals(HgSettings.systemDefault, HgSettings.fallbackEncoding)
        assertTrue(HgSettings.widenToFragments)
        assertTrue(HgSettings.ignoreEolChanges)
        assertTrue(HgSettings.statusInTabs)
        assertEquals(ChangesetsFragments.PLATFORM_TRIMMED, HgSettings.changesetsFragments)
        assertEquals(HgServerPool.DEFAULT_SERVERS, HgSettings.commandServerCount)
        assertEquals(100, HgSettings.largestBranch)
    }

    @Test
    fun `the largest branch accepts a whole number of revisions, zero included`() {
        assertEquals(250, HgSettings.branchLimitChoice(" 250 "))
        assertEquals(0, HgSettings.branchLimitChoice("0"))
        assertNull(HgSettings.branchLimitChoice("-1"))
        assertNull(HgSettings.branchLimitChoice(""))
        assertNull(HgSettings.branchLimitChoice("1.5"))
        assertNull(HgSettings.branchLimitChoice("many"))
        assertNull(HgSettings.branchLimitChoice("99999999999"))
    }

    @Test
    fun `switches keep the keys and defaults they were saved under`() {
        assertEquals(
            mapOf(
                "mercurial.changesetsWiden" to true,
                "mercurial.ignoreEolChanges" to true,
                "mercurial.statusInTabs" to true,
                "mercurial.commandServer" to true
            ),
            HgSettings.flagDefaults
        )
    }

    @Test
    fun `a server count outside the offered bounds is pulled back into them`() {
        assertEquals(HgServerPool.MIN_SERVERS, HgSettings.boundCount(0))
        assertEquals(HgServerPool.MAX_SERVERS, HgSettings.boundCount(12))
        assertEquals(3, HgSettings.boundCount(3))
    }

    @Test
    fun `a cleared encoding field stands for the system encoding`() {
        assertEquals(HgSettings.systemDefault, HgSettings.encodingChoice("  "))
        assertEquals("UTF-8", HgSettings.encodingChoice(" UTF-8 "))
    }

    @Test
    fun `only an encoding the runtime knows is accepted`() {
        assertTrue(HgSettings.isKnownCharset("windows-1251"))
        assertFalse(HgSettings.isKnownCharset("no-such-encoding"))
        assertFalse(HgSettings.isKnownCharset("bad name!"))
    }

    @Test
    fun `the fallback charset is resolved without a running application`() {
        assertNotNull(HgSettings.fallbackCharset())
    }
}
