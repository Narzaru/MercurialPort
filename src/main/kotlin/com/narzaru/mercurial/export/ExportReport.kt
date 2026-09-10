package com.narzaru.mercurial.export

import com.narzaru.mercurial.hg.HgPaths
import java.io.File

object ExportReport {

    const val EXTERNAL_DIR = "_External"

    private const val LISTED_FAILURES = 10

    fun destination(sourcePath: String, projectRoot: String): String =
        HgPaths.relativize(sourcePath, projectRoot) ?: File(EXTERNAL_DIR, File(sourcePath).name).path

    fun message(copied: Int, failures: List<String>, targetPath: String): String {
        val text = StringBuilder("Files copied: $copied\nPath: $targetPath")
        if (failures.isEmpty()) return text.toString()
        text.append("\nFailed: ${failures.size}")
        for (failure in failures.take(LISTED_FAILURES)) text.append("\n").append(failure)
        if (failures.size > LISTED_FAILURES) text.append("\n…")
        return text.toString()
    }
}
