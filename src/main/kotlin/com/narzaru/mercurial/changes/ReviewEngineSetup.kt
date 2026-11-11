package com.narzaru.mercurial.changes

import com.intellij.diff.comparison.CancellationChecker
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.narzaru.mercurial.hg.HgCommandRunner
import com.narzaru.mercurial.review.ReviewChanges
import com.narzaru.mercurial.review.ReviewLineDiff
import com.narzaru.mercurial.review.ReviewOptions
import java.util.concurrent.Callable
import java.util.concurrent.Future

object ProgressAwareTaskLauncher : TaskLauncher {

    override fun <T> launch(task: Callable<T>): Future<T> {
        val indicator = ProgressManager.getInstance().progressIndicator ?: return PooledTaskLauncher.launch(task)
        return PooledTaskLauncher.launch(
            Callable {
                val result = ArrayList<T>(1)
                ProgressManager.getInstance().executeProcessUnderProgress({ result.add(task.call()) }, indicator)
                result.single()
            }
        )
    }
}

object ReviewEngineSetup {

    private val progressChecker = object : CancellationChecker {
        override fun checkCanceled() = ProgressManager.checkCanceled()
    }

    fun create(runner: HgCommandRunner): ReviewChanges = ReviewChanges(
        commands = cancellable(HgCommands.of(runner)),
        options = ReviewOptions(),
        jobs = ParallelJobs(ProgressAwareTaskLauncher),
        lineDiff = ReviewLineDiff(progressChecker)
    )

    private fun cancellable(commands: HgCommands): HgCommands = HgCommands { args ->
        val result = commands.run(args)
        if (!result.success) {
            ProgressManager.checkCanceled()
            if (Thread.currentThread().isInterrupted) throw ProcessCanceledException()
        }
        result
    }
}
