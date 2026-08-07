package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class HgPathsTest {

    @Test
    fun `обратные слэши приводятся к прямым`() {
        assertEquals("src/main/a.kt", HgPaths.normalize("src\\main\\a.kt"))
    }

    @Test
    fun `ключ нормализован и в нижнем регистре`() {
        assertEquals("src/a.kt", HgPaths.key("SRC\\A.kt"))
    }

    @Test
    fun `путь внутри корня становится относительным`() {
        assertEquals("src/a.kt", HgPaths.relativize("/repo/src/a.kt", "/repo"))
    }

    @Test
    fun `регистр корня не мешает`() {
        assertEquals("src\\a.kt", HgPaths.relativize("D:\\Repo\\src\\a.kt", "d:\\repo"))
    }

    @Test
    fun `путь вне корня относительным не считается`() {
        assertNull(HgPaths.relativize("/other/a.kt", "/repo"))
    }

    @Test
    fun `для файла вне корня остаётся одно имя`() {
        val relative = HgPaths.relativize(File("/other/a.kt"), File("/repo"))

        assertEquals("a.kt", relative)
    }
}
