package com.narzaru.mercurial.export

import com.narzaru.mercurial.hg.HgPaths
import java.io.File

object ExportReport {

    const val EXTERNAL_DIR = "_External"

    private const val LISTED_FAILURES = 10

    fun destination(sourcePath: String, projectRoot: String): String =
        destinations(listOf(sourcePath), projectRoot).single()

    fun destinations(sourcePaths: List<String>, projectRoot: String): List<String> {
        val inProject = sourcePaths.map { HgPaths.relativize(it, projectRoot) }
        val taken = inProject.filterNotNull().mapTo(HashSet()) { HgPaths.key(it) }
        return sourcePaths.zip(inProject) { source, relative ->
            relative ?: externalDestination(File(source).name, taken)
        }
    }

    private fun externalDestination(name: String, taken: MutableSet<String>): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var candidate = File(EXTERNAL_DIR, name).path
        var copy = 1
        while (!taken.add(HgPaths.key(candidate))) {
            copy++
            candidate = File(EXTERNAL_DIR, "${name.take(dot)} ($copy)${name.drop(dot)}").path
        }
        return candidate
    }

    fun message(copied: Int, failures: List<String>, targetPath: String): String {
        val text = StringBuilder("Files copied: $copied\nPath: $targetPath")
        if (failures.isEmpty()) return text.toString()
        text.append("\nFailed: ${failures.size}")
        for (failure in failures.take(LISTED_FAILURES)) text.append("\n").append(failure)
        if (failures.size > LISTED_FAILURES) text.append("\n…")
        return text.toString()
    }
}
