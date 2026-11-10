package com.narzaru.mercurial.hg

object HgOpenBranches {

    private const val DEFAULT_BRANCH = "default"

    private const val DEFAULT_REVISIONS = "branch('re:^$DEFAULT_BRANCH\$')"

    const val REVSET =
        "head() and ($DEFAULT_REVISIONS or (not closed() and not ancestors(head() and $DEFAULT_REVISIONS)))"

    val ARGUMENTS = listOf("log", "-r", REVSET, "--template", "{branch}\\n")

    private val ORDER: Comparator<String> = compareBy<String> { it != DEFAULT_BRANCH }
        .then(String.CASE_INSENSITIVE_ORDER)
        .thenBy { it }

    fun parse(output: String): List<String> = output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedWith(ORDER)
        .toList()
}
