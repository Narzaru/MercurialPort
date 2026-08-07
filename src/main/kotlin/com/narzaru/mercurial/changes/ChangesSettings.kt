package com.narzaru.mercurial.changes

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/**
 * Настройки окна Hg Changes, живущие в проекте: фильтры, видимость элементов и
 * отметки «просмотрено». Собраны в одном месте, чтобы ключи не были размазаны
 * строковыми литералами по панели.
 */
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

    var showUnchanged: Boolean
        get() = props.getBoolean(key("showUnchanged"), false)
        set(value) = props.setValue(key("showUnchanged"), value, false)

    var statsColumnVisible: Boolean
        get() = props.getBoolean(key("statsColumn"), true)
        set(value) = props.setValue(key("statsColumn"), value, true)

    /**
     * Отмечать файл просмотренным, как только он открыт (двойной клик или переход на его
     * вкладку в редакторе). Ревью идёт файл за файлом, и ставить отметку вручную после
     * каждого — лишний шаг; кому мешает, выключает пунктом в меню ⋮.
     */
    var markReviewedOnOpen: Boolean
        get() = props.getBoolean(key("markReviewedOnOpen"), true)
        set(value) = props.setValue(key("markReviewedOnOpen"), value, true)

    override fun load(): List<String> = props.getList(key("reviewed")).orEmpty()

    override fun save(paths: List<String>) = props.setList(key("reviewed"), paths)

    private fun key(name: String) = PREFIX + name

    private companion object {
        const val PREFIX = "mercurial."
        const val DEFAULT_BRANCH = "default"
    }
}
