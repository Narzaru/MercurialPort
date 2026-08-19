package com.narzaru.mercurial.hg

import com.intellij.execution.configurations.GeneralCommandLine
import java.io.File

data class HgResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val success: Boolean get() = exitCode == 0
}

/** Результат с сырым stdout — для команд, где важны исходные байты (`hg cat`). */
class HgBytesResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)

/**
 * Запускает командную строку Mercurial (`hg`) в указанном рабочем каталоге.
 * Аргументы передаются списком, без ручного экранирования кавычек.
 */
class HgCommandRunner(private val workDir: File) {

    fun run(vararg args: String): HgResult = run(args.toList())

    fun run(args: List<String>): HgResult {
        return try {
            val (exit, out, err) = exec(args)
            HgResult(exit, HgOutputDecoder.decode(out), HgOutputDecoder.decode(err))
        } catch (e: Exception) {
            HgResult(-1, "", e.message ?: e.toString())
        }
    }

    /** Возвращает сырой stdout (для `hg cat`, где важны исходные байты). */
    fun runToBytes(args: List<String>): Pair<Int, ByteArray> {
        val res = runToBytesDetailed(args)
        return res.exitCode to res.stdout
    }

    /** То же, но с текстом ошибки: молчаливый отказ `hg cat` иначе не отличить от пустого файла. */
    fun runToBytesDetailed(args: List<String>): HgBytesResult {
        return try {
            val (exit, out, err) = exec(args)
            HgBytesResult(exit, out, HgOutputDecoder.decode(err))
        } catch (e: Exception) {
            HgBytesResult(-1, ByteArray(0), e.message ?: e.toString())
        }
    }

    private fun exec(args: List<String>): Triple<Int, ByteArray, ByteArray> {
        val cmd = GeneralCommandLine(listOf(HG) + args).withWorkingDirectory(workDir.toPath())
        val process = cmd.createProcess()

        // stderr читаем в отдельном потоке, чтобы не поймать взаимоблокировку
        // при переполнении пайпа, пока висим на чтении stdout.
        var errBytes = ByteArray(0)
        val errThread = Thread { errBytes = process.errorStream.readBytes() }
        errThread.isDaemon = true
        errThread.start()

        val outBytes = process.inputStream.readBytes()
        process.waitFor()
        errThread.join()

        return Triple(process.exitValue(), outBytes, errBytes)
    }

    companion object {
        private const val HG = "hg"

        /** Ищет корень репозитория вверх по дереву каталогов (наличие `.hg`). */
        fun findRepoRoot(start: File?): File? {
            var dir = start
            while (dir != null) {
                if (File(dir, ".hg").isDirectory) return dir
                dir = dir.parentFile
            }
            return null
        }
    }
}
