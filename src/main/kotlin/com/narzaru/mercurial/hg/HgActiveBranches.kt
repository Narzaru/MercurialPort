package com.narzaru.mercurial.hg

object HgActiveBranches {

    private const val ACTIVE = "a "

    private const val DEFAULT_BRANCH = "default"

    val ARGUMENTS = listOf("branches", "--template", "{if(active,'a','i')} {branch}\\n")

    private val ORDER: Comparator<String> = compareBy<String> { it != DEFAULT_BRANCH }
        .then(String.CASE_INSENSITIVE_ORDER)
        .thenBy { it }

    fun parse(output: String): List<String> = output.lineSequence()
        .filter { it.startsWith(ACTIVE) }
        .map { it.substring(ACTIVE.length).trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedWith(ORDER)
        .toList()
}
