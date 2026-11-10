package com.narzaru.mercurial.changes

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

class ChangesSettings(project: Project) : ReviewMarksStore {

    private val props = PropertiesComponent.getInstance(project)

    var filter: String
        get() = props.getValue(key("filter"), "")
        set(value) = props.setValue(key("filter"), value)

    var exclude: String
        get() = props.getValue(key("exclude"), "")
        set(value) = props.setValue(key("exclude"), value)

    var compareBranch: String
        get() = props.getValue(key("compareBranch"), DEFAULT_BRANCH)
        set(value) = props.setValue(key("compareBranch"), value)

    var filtersVisible: Boolean
        get() = props.getBoolean(key("filtersVisible"), false)
        set(value) = props.setValue(key("filtersVisible"), value, false)

    var showUntracked: Boolean
        get() = props.getBoolean(key("showUntracked"), false)
        set(value) = props.setValue(key("showUntracked"), value, false)

    var showUnchanged: Boolean
        get() = props.getBoolean(key("showUnchanged"), false)
        set(value) = props.setValue(key("showUnchanged"), value, false)

    var revisionsVisible: Boolean
        get() = props.getBoolean(key("revisionsVisible"), true)
        set(value) = props.setValue(key("revisionsVisible"), value, true)

    private val overrides = RevisionOverrides(object : RevisionOverridesStore {
        override fun branchOrder(): List<String> = props.getList(key(REVISION_BRANCHES)).orEmpty()
        override fun saveBranchOrder(branches: List<String>) = saveList(key(REVISION_BRANCHES), branches)
        override fun overrides(branch: String): List<String> = props.getList(revisionsKey(branch)).orEmpty()
        override fun saveOverrides(branch: String, values: List<String>) = saveList(revisionsKey(branch), values)
    })

    fun revisionOverrides(branch: String): List<String> = overrides.get(branch)

    fun setRevisionOverrides(branch: String, values: List<String>) = overrides.set(branch, values)

    var statsColumnVisible: Boolean
        get() = props.getBoolean(key("statsColumn"), true)
        set(value) = props.setValue(key("statsColumn"), value, true)

    var markReviewedOnOpen: Boolean
        get() = props.getBoolean(key("markReviewedOnOpen"), true)
        set(value) = props.setValue(key("markReviewedOnOpen"), value, true)

    var selectOpenedFile: Boolean
        get() = props.getBoolean(key("selectOpenedFile"), false)
        set(value) = props.setValue(key("selectOpenedFile"), value, false)

    init {
        props.unsetValue(key(LEGACY_REVIEWED))
        props.unsetValue(key(LEGACY_OPEN_DIFF_INSTEAD_OF_FILE))
    }

    override fun branchOrder(): List<String> = props.getList(key(REVIEW_BRANCHES)).orEmpty()

    override fun saveBranchOrder(branches: List<String>) = saveList(key(REVIEW_BRANCHES), branches)

    override fun marks(branch: String): List<String> = props.getList(marksKey(branch)).orEmpty()

    override fun saveMarks(branch: String, entries: List<String>) = saveList(marksKey(branch), entries)

    private fun saveList(name: String, values: List<String>) {
        if (values.isEmpty()) props.unsetValue(name) else props.setList(name, values)
    }

    private fun marksKey(branch: String) = key(REVIEW_MARKS_PREFIX + ReviewMarks.storageName(branch))

    private fun revisionsKey(branch: String) = key(REVISIONS_PREFIX + branch)

    private fun key(name: String) = PREFIX + name

    private companion object {
        const val PREFIX = "mercurial."
        const val DEFAULT_BRANCH = "default"
        const val LEGACY_REVIEWED = "reviewed"
        const val LEGACY_OPEN_DIFF_INSTEAD_OF_FILE = "openDiffInsteadOfFile"
        const val REVIEW_BRANCHES = "reviewMarks.branches"
        const val REVIEW_MARKS_PREFIX = "reviewMarks.branch."
        const val REVISIONS_PREFIX = "revisions."
        const val REVISION_BRANCHES = "revisionOverrides.branches"
    }
}
