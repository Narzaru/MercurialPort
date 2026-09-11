package com.narzaru.mercurial.changes

data class RepoState(val branch: String, val node: String) {

    val isKnown: Boolean get() = node.isNotEmpty()

    companion object {

        val UNKNOWN = RepoState("", "")

        const val TEMPLATE = "{branch}|{node|short}"

        val ARGUMENTS = listOf("log", "-r", ".", "--template", TEMPLATE)

        private const val MISSING = "?"

        fun parse(output: String): RepoState {
            val parts = output.trim().split('|')
            if (parts.size < 2) return UNKNOWN
            val node = parts[1].trim()
            if (node.isEmpty() || node == MISSING) return UNKNOWN
            return RepoState(parts[0].trim(), node)
        }

        fun changed(known: RepoState, probed: RepoState): Boolean =
            known.isKnown && probed.isKnown && known != probed
    }
}
