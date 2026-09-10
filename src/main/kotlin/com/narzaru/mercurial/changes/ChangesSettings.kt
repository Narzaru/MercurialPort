package com.narzaru.mercurial.changes

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

class ChangesSettings(project: Project) : ReviewedPathsStore {

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

    fun revisionOverrides(branch: String): List<String> =
        props.getList(key("revisions.$branch")).orEmpty()

    fun setRevisionOverrides(branch: String, values: List<String>) =
        props.setList(key("revisions.$branch"), values)

    var statsColumnVisible: Boolean
        get() = props.getBoolean(key("statsColumn"), true)
        set(value) = props.setValue(key("statsColumn"), value, true)

    var markReviewedOnOpen: Boolean
        get() = props.getBoolean(key("markReviewedOnOpen"), true)
        set(value) = props.setValue(key("markReviewedOnOpen"), value, true)

    var openDiffInsteadOfFile: Boolean
        get() = props.getBoolean(key("openDiffInsteadOfFile"), false)
        set(value) = props.setValue(key("openDiffInsteadOfFile"), value, false)

    var selectOpenedFile: Boolean
        get() = props.getBoolean(key("selectOpenedFile"), false)
        set(value) = props.setValue(key("selectOpenedFile"), value, false)

    override fun load(): List<String> = props.getList(key("reviewed")).orEmpty()

    override fun save(paths: List<String>) = props.setList(key("reviewed"), paths)

    private fun key(name: String) = PREFIX + name

    private companion object {
        const val PREFIX = "mercurial."
        const val DEFAULT_BRANCH = "default"
    }
}
