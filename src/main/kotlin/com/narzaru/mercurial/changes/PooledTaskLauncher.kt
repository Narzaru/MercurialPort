package com.narzaru.mercurial.changes

import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.Callable
import java.util.concurrent.Future

object PooledTaskLauncher : TaskLauncher {

    override fun <T> launch(task: Callable<T>): Future<T> =
        ApplicationManager.getApplication().executeOnPooledThread(task)
}
