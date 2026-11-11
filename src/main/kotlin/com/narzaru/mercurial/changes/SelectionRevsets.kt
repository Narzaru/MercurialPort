package com.narzaru.mercurial.changes

object SelectionRevsets {

    const val OWN_COMMITS = "${BranchRevisions.OWN_REVS} and not merge()"

    private class Universe(val revset: String, val contains: (HgRevision) -> Boolean)

    private class Candidate(val revset: String, val listedNodes: Int)

    private val universes = listOf(
        Universe(OWN_COMMITS) { !it.isMerge },
        Universe(BranchRevisions.OWN_REVS) { true }
    )

    fun selected(revisions: List<HgRevision>, selection: RevisionSelection): String {
        val wanted = revisions.filter { selection.isSelected(it) }
        val relative = universes.map { universe ->
            val members = revisions.filter(universe.contains)
            adjusted(
                universe.revset,
                excluded = members.filterNot { selection.isSelected(it) }.map { it.node },
                included = wanted.filterNot(universe.contains).map { it.node }
            )
        }
        return fewestListed(relative + listed(wanted.map { it.node }))
    }

    fun base(revisions: List<HgRevision>, selection: RevisionSelection): String {
        val oldest = revisions.filter { selection.isSelected(it) }.minByOrNull { order(it) }
        return if (oldest == null) BranchRevisions.NO_REVISION else "p1(${oldest.node})"
    }

    fun review(revisions: List<HgRevision>, selection: RevisionSelection): String? {
        val wanted = revisions.filter { selection.isSelected(it) }
        val first = wanted.minByOrNull { order(it) } ?: return null
        val last = wanted.maxBy { order(it) }
        val selected = selected(revisions, selection)
        if (first == last) return selected
        return "($selected) + ((${first.node}::${last.node}) and merge() and (${BranchRevisions.OWN_REVS}))"
    }

    private fun order(revision: HgRevision): Long = revision.rev.toLongOrNull() ?: Long.MAX_VALUE

    fun subset(universe: String, members: List<String>, wanted: Collection<String>): String {
        val wantedSet = wanted.toSet()
        val memberSet = members.toSet()
        val excluded = members.filterNot { it in wantedSet }
        val outside = wanted.filterNot { it in memberSet }
        return fewestListed(listOf(listed(wanted), adjusted(universe, excluded, outside)))
    }

    private fun listed(nodes: Collection<String>) = Candidate(BranchRevisions.revset(nodes), nodes.size)

    private fun adjusted(universe: String, excluded: List<String>, included: List<String>): Candidate {
        var revset = universe
        if (excluded.isNotEmpty()) revset = "($revset) - (${BranchRevisions.revset(excluded)})"
        if (included.isNotEmpty()) revset = "($revset) + ${BranchRevisions.revset(included)}"
        return Candidate(revset, excluded.size + included.size)
    }

    private fun fewestListed(candidates: List<Candidate>): String = candidates.minBy { it.listedNodes }.revset
}
