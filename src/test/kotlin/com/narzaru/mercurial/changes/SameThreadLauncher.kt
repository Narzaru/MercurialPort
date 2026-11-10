package com.narzaru.mercurial.changes

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

object SameThreadLauncher : TaskLauncher {

    override fun <T> launch(task: Callable<T>): Future<T> = CompletableFuture.completedFuture(task.call())
}
