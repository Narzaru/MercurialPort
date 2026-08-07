package com.narzaru.mercurial.changes

/**
 * Фильтр списка файлов по подстрокам. `include` пустой — проходит всё; `exclude`
 * сильнее `include`, чтобы «показать всё, кроме тестов» работало одним полем.
 *
 * Шаблон сначала проверяется целиком (тогда пробелы внутри значимы: `Tests/Ray`),
 * а потом по словам — так `Comm Tests` находит и то, и другое.
 */
class PathFilter(include: String, exclude: String) {

    private val include = include.trim()
    private val exclude = exclude.trim()

    fun accepts(path: String): Boolean {
        if (exclude.isNotEmpty() && matches(path, exclude)) return false
        if (include.isEmpty()) return true
        return matches(path, include)
    }

    private fun matches(path: String, patterns: String): Boolean {
        if (path.contains(patterns, ignoreCase = true)) return true
        return patterns.split(' ').filter { it.isNotBlank() }
            .any { path.contains(it, ignoreCase = true) }
    }

    companion object {
        /** Фильтр, пропускающий всё. */
        val ALL = PathFilter("", "")
    }
}
