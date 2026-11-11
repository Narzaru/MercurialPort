package com.narzaru.mercurial.review

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest

class ReviewChangesValidation {

    private class Review(val id: String, val revisions: List<String>)

    @Test
    fun `engine output for real reviews`() {
        val config = System.getenv(CONFIG_VARIABLE)
        assumeTrue("$CONFIG_VARIABLE is not set", !config.isNullOrBlank())
        val root = JsonParser.parseString(File(config!!).readText()).asJsonObject
        val repo = File(root["repo"].asString)
        val outputDir = File(root["output"].asString).also { it.mkdirs() }
        val hgArgs = root["hgArgs"]?.asJsonArray?.map { it.asString }.orEmpty()
        val fallback = root["fallbackCharset"]?.asString?.let { Charset.forName(it) } ?: Charset.defaultCharset()
        val options = ReviewOptions(
            baseBranch = root["baseBranch"]?.asString ?: "default",
            renameSimilarity = root["renameSimilarity"]?.asDouble ?: 0.6
        )
        val only = System.getenv(ONLY_VARIABLE)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val reviews = root["reviews"].asJsonArray.map { item ->
            val review = item.asJsonObject
            Review(review["id"].asString, review["revisions"].asJsonArray.map { it.asString })
        }.filter { only == null || it.id in only }

        val gson = GsonBuilder().serializeNulls().create()
        CommandServerHg(repo, hgArgs, fallback).use { hg ->
            for (review in reviews) {
                hg.resetCounters()
                val began = System.nanoTime()
                val changes = ReviewChanges(hg, options)
                val load = changes.load(review.revisions)
                check(load.error == null) { "${review.id}: ${load.error}" }
                val loadNanos = System.nanoTime() - began
                val loadCalls = hg.calls
                val diffs = changes.diffs(load, load.files)
                val totalNanos = System.nanoTime() - began
                val result = linkedMapOf(
                    "reviewId" to review.id,
                    "R" to load.revisions.map { it.node },
                    "N" to load.revisions.filterNot { it.isMerge }.map { it.node },
                    "files" to diffs.map { record(it, load, changes, hg) },
                    "time_total" to totalNanos / 1e9,
                    "time_load" to loadNanos / 1e9,
                    "time_hg" to hg.nanos / 1e9,
                    "hg_calls" to hg.calls,
                    "hg_calls_load" to loadCalls,
                    "hg_calls_by_command" to LinkedHashMap(hg.callsByCommand),
                    "annotate_time" to 0.0,
                    "annotate_count" to 0,
                    "annotate_needed" to diffs.count { diff -> diff.steps.any { it.kind != ReviewStepKind.REVIEW } },
                    "renames" to renames(load, hg)
                )
                File(outputDir, "local_${review.id}.json").writeText(gson.toJson(result))
                println(
                    "${review.id}: ${load.files.size} files, ${hg.calls} hg calls ($loadCalls for the list), " +
                        "%.2f s (list %.2f s, hg %.2f s)".format(totalNanos / 1e9, loadNanos / 1e9, hg.nanos / 1e9)
                )
            }
        }
    }

    private fun record(diff: ReviewFileDiff, load: ReviewLoad, changes: ReviewChanges, hg: CommandServerHg): Map<String, Any?> {
        val file = diff.file
        val old = file.oldRev?.let { hg.rawContent(it, file.oldPath) }
        val new = file.newRev?.let { hg.rawContent(it, file.path) }
        val record = linkedMapOf<String, Any?>(
            "path" to file.path,
            "opath" to file.oldPath,
            "copy" to when (file.type) {
                ReviewChangeType.RENAMED -> "rename"
                ReviewChangeType.COPIED -> "copy"
                else -> null
            },
            "type" to file.type.name,
            "first" to file.revisions.first(),
            "O" to file.baseRev,
            "H" to file.headRev,
            "T" to file.revisions,
            "old_exists" to (file.oldRev != null),
            "new_exists" to (file.newRev != null),
            "old_hash" to old?.let { hash(it) },
            "new_hash" to new?.let { hash(it) },
            "binary" to file.binary,
            "old_len" to diff.oldLines.size,
            "new_len" to diff.newLines.size,
            "traced" to changes.needsTrace(load, file)
        )
        if (file.binary) return record
        record["opcodes"] = diff.hunks.map { opcode(it) }
        record["shown"] = diff.shownHunks.map {
            listOf(it.fragment.left.start, it.fragment.left.end, it.fragment.right.start, it.fragment.right.end)
        }
        val attribution = diff.attribution ?: return record
        val steps = diff.steps
        record["steps"] = steps.map { listOf(it.node, kind(it.kind), null, it.differsFromBothParents) }
        record["o_fate"] = attribution.removedBy.map { step -> step?.let { listOf(steps[it].node, kind(steps[it].kind), false) } }
        record["h_origin"] = attribution.addedBy.map { step ->
            if (step == null) listOf("O", 0) else listOf(steps[step].node, kind(steps[step].kind), false)
        }
        record["needs_annotate"] = steps.any { it.kind != ReviewStepKind.REVIEW }
        return record
    }

    private fun renames(load: ReviewLoad, hg: CommandServerHg): List<List<Any?>> {
        val diff = ReviewLineDiff()
        fun lines(rev: String, path: String): List<String> {
            val res = hg.run("cat", "-r", rev, "path:$path")
            return ReviewText.lines(if (res.success) res.stdout else null)
        }
        return load.revisions.filterNot { it.isMerge }.flatMap { revision ->
            revision.changes.filter { it.copiedFrom.isNotEmpty() }.map { item ->
                val similarity = revision.p1?.let { diff.similarity(lines(it, item.copiedFrom), lines(revision.node, item.path)) }
                listOf(revision.node, item.copiedFrom, item.path, similarity, item.status.name)
            }
        }
    }

    private fun opcode(hunk: ReviewHunk): List<Any> {
        val left = hunk.fragment.left
        val right = hunk.fragment.right
        val tag = when {
            left.isEmpty -> "insert"
            right.isEmpty -> "delete"
            else -> "replace"
        }
        return listOf(tag, left.start, left.end, right.start, right.end)
    }

    private fun kind(kind: ReviewStepKind): String = when (kind) {
        ReviewStepKind.REVIEW -> "review"
        ReviewStepKind.REVIEW_MERGE -> "review_merge"
        ReviewStepKind.MERGE -> "merge"
        ReviewStepKind.OTHER -> "other"
    }

    private fun hash(bytes: ByteArray): String {
        var data = bytes
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            data = data.copyOfRange(3, data.size)
        }
        val normalized = java.io.ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            if (data[i] == '\r'.code.toByte() && i + 1 < data.size && data[i + 1] == '\n'.code.toByte()) i++
            normalized.write(data[i].toInt())
            i++
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private companion object {
        const val CONFIG_VARIABLE = "UPSOURCE_LIKE_VALIDATE"
        const val ONLY_VARIABLE = "UPSOURCE_LIKE_ONLY"
    }
}
