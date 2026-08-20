package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HgRenameParserTest {

    @Test
    fun `берёт старое имя без хеша filelog-а`() {
        val out = "src/new.kt renamed from src/old.kt:0123456789abcdef0123456789abcdef01234567"

        assertEquals("src/old.kt", HgRenameParser.sourceOf(out))
    }

    @Test
    fun `не переименован — null`() {
        assertNull(HgRenameParser.sourceOf("src\\a.kt not renamed"))
    }

    @Test
    fun `путь с обратными слэшами нормализуется`() {
        val out = "src\\new.kt renamed from src\\old.kt:abc"

        assertEquals("src/old.kt", HgRenameParser.sourceOf(out))
    }

    @Test
    fun `пустой вывод — null`() {
        assertNull(HgRenameParser.sourceOf(""))
    }
}
