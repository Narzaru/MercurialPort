package com.narzaru.mercurial.hg

import java.security.MessageDigest

object HgHash {

    const val LENGTH = 16

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-1")

    fun text(digest: ByteArray): String {
        val text = StringBuilder(LENGTH)
        for (index in 0 until LENGTH / 2) {
            val value = digest[index].toInt() and 0xFF
            text.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return text.toString()
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}
