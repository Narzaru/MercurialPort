package com.narzaru.mercurial.hg

object HgFailure {

    const val HG_ERROR_PREFIX = "HG Error"
    const val NOT_STARTED_PREFIX = "Cannot run hg"

    private fun prefix(failedToStart: Boolean): String =
        if (failedToStart) NOT_STARTED_PREFIX else HG_ERROR_PREFIX

    fun message(failedToStart: Boolean, details: String): String =
        "${prefix(failedToStart)}: ${details.trim()}"

    fun message(result: HgResult): String = message(result.failedToStart, result.stderr)
}

internal enum class HgFailureKind(val exitCode: Int) {
    NOT_STARTED(-1),
    CANCELED(-1),
    TIMED_OUT(-2);

    fun result(message: String): HgBytesResult =
        HgBytesResult(exitCode, ByteArray(0), message, failedToStart = this == NOT_STARTED)
}
