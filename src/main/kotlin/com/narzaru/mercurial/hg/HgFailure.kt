package com.narzaru.mercurial.hg

object HgFailure {

    const val HG_ERROR_PREFIX = "HG Error"
    const val NOT_STARTED_PREFIX = "Cannot run hg"

    fun prefix(failedToStart: Boolean): String =
        if (failedToStart) NOT_STARTED_PREFIX else HG_ERROR_PREFIX

    fun message(failedToStart: Boolean, details: String): String =
        "${prefix(failedToStart)}: ${details.trim()}"

    fun message(result: HgResult): String = message(result.failedToStart, result.stderr)
}
