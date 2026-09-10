package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.ChangesetsFragments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    }

    @Test
    fun `a server count outside the offered bounds is pulled back into them`() {
        assertEquals(HgServerPool.MIN_SERVERS, HgSettings.boundCount(0))
        assertEquals(HgServerPool.MAX_SERVERS, HgSettings.boundCount(12))
        assertEquals(3, HgSettings.boundCount(3))
    }

    @Test
    fun `the fallback charset is resolved without a running application`() {
        assertNotNull(HgSettings.fallbackCharset())
    }
}
