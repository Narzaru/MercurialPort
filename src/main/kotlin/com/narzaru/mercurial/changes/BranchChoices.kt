package com.narzaru.mercurial.changes

class BranchChoices {

    var names: List<String> = emptyList()
        private set

    private var generation = 0

    private var fetchedGeneration = -1

    private var fetching = false

    fun invalidate() {
        generation++
    }

    fun startFetch(): Int? {
        if (fetching || fetchedGeneration == generation) return null
        fetching = true
        return generation
    }

    fun fetched(stamp: Int, fetchedNames: List<String>?): Boolean {
        fetching = false
        if (fetchedNames == null) return false
        if (stamp > fetchedGeneration) fetchedGeneration = stamp
        if (fetchedNames == names) return false
        names = fetchedNames
        return true
    }
}
