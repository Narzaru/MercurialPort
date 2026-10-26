package com.narzaru.mercurial.hg

import java.io.File
import java.io.IOException
import java.io.InputStream

object HgContentHash {

    const val PREFIX = "c:"

    private const val BUFFER_SIZE = 64 * 1024

    fun of(input: InputStream): String {
        val digest = HgHash.newDigest()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return PREFIX + HgHash.text(digest.digest())
    }

    fun ofFile(file: File): String? {
        if (!file.isFile) return null
        return try {
            file.inputStream().use { of(it) }
        } catch (_: IOException) {
            null
        }
    }
}
