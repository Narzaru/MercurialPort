package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.ChangesetsEngine
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
    fun `every fragment choice is read back under the name it is stored by`() {
        for (choice in ChangesetsFragments.entries) {
            assertEquals(choice, ChangesetsFragments.fromStored(choice.name))
        }
    }

    @Test
    fun `a missing or unknown fragment choice falls back to the default`() {
        assertEquals(ChangesetsFragments.PLATFORM_TRIMMED, ChangesetsFragments.DEFAULT)
        assertEquals(ChangesetsFragments.DEFAULT, ChangesetsFragments.fromStored(null))
        assertEquals(ChangesetsFragments.DEFAULT, ChangesetsFragments.fromStored("NO_SUCH_ENGINE"))
    }

    @Test
    fun `a fragment choice stored as the review engine falls back to the default`() {
        assertEquals(ChangesetsFragments.DEFAULT, ChangesetsFragments.fromStored("UPSOURCE"))
    }

    @Test
    fun `every engine choice is read back under the name it is stored by`() {
        for (choice in ChangesetsEngine.entries) {
            assertEquals(choice, ChangesetsEngine.fromStored(choice.name))
        }
        assertEquals(listOf("PLUGIN", "UPSOURCE"), ChangesetsEngine.entries.map { it.name })
    }

    @Test
    fun `the plugin's own rules build changesets unless another engine is stored`() {
        assertEquals(ChangesetsEngine.PLUGIN, ChangesetsEngine.DEFAULT)
        assertEquals(ChangesetsEngine.DEFAULT, HgSettings.changesetsEngine)
        assertEquals(ChangesetsEngine.DEFAULT, ChangesetsEngine.fromStored(null))
        assertEquals(ChangesetsEngine.DEFAULT, ChangesetsEngine.fromStored("PLATFORM_TRIMMED"))
    }

    @Test
    fun `the review engine runs only when Upsource review is chosen`() {
        assertTrue(ChangesetsEngine.UPSOURCE.isUpsourceReview)
        assertFalse(ChangesetsEngine.PLUGIN.isUpsourceReview)
        assertFalse(HgSettings.usesReviewEngine)
    }

    @Test
    fun `the own rules' switches are open only while the own rules are chosen`() {
        assertEquals(ChangesetsDiffControls(true, true), ChangesetsDiffControls.of(ChangesetsEngine.PLUGIN, true))
        assertEquals(ChangesetsDiffControls(true, false), ChangesetsDiffControls.of(ChangesetsEngine.PLUGIN, false))
        assertEquals(ChangesetsDiffControls(false, false), ChangesetsDiffControls.of(ChangesetsEngine.UPSOURCE, true))
        assertEquals(ChangesetsDiffControls(false, false), ChangesetsDiffControls.of(ChangesetsEngine.UPSOURCE, false))
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
