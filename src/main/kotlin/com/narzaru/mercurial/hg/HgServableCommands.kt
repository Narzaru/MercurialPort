package com.narzaru.mercurial.hg

object HgServableCommands {

    private val READ_ONLY = setOf(
        "annotate",
        "blame",
        "branches",
        "cat",
        "debugancestor",
        "debugrename",
        "diff",
        "files",
        "heads",
        "id",
        "identify",
        "locate",
        "log",
        "manifest",
        "parents",
        "paths",
        "root",
        "status",
        "summary",
        "tags",
        "tip"
    )

    private const val SHORTEST_ABBREVIATION = 3

    private val FORBIDDEN_SHORT = setOf("-R", "-o")

    private val FORBIDDEN_LONG = setOf(
        "--config",
        "--configfile",
        "--cwd",
        "--debugger",
        "--encoding",
        "--encodingmode",
        "--output",
        "--profile",
        "--repository"
    )

    fun isServable(args: List<String>): Boolean {
        val command = args.firstOrNull() ?: return false
        if (command !in READ_ONLY) return false
        return args.drop(1).none { isForbidden(it) }
    }

    private fun isForbidden(argument: String): Boolean {
        if (!argument.startsWith("-")) return false
        val name = argument.substringBefore('=')
        if (FORBIDDEN_SHORT.any { name == it || name.startsWith(it) }) return true
        if (name.length < SHORTEST_ABBREVIATION) return false
        return FORBIDDEN_LONG.any { it.startsWith(name) }
    }
}
