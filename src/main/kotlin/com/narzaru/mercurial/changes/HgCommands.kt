package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgCommandRunner
import com.narzaru.mercurial.hg.HgResult

fun interface HgCommands {

    fun run(args: List<String>): HgResult

    fun run(vararg args: String): HgResult = run(args.toList())

    companion object {

        fun of(runner: HgCommandRunner): HgCommands = HgCommands { runner.runToText(it) }
    }
}
