package com.narzaru.mercurial.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ToggleDiffContextTest : BasePlatformTestCase() {

    fun `test the editor inside a diff leaves jump to source to the platform`() {
        val comparedFile = myFixture.configureByText("a.txt", "text").virtualFile
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.VIRTUAL_FILE, comparedFile)
            .add(DiffDataKeys.DIFF_CONTEXT, FakeDiffContext(project))
            .build()

        assertEquals(ToggleDiffTarget.None, ToggleDiffContext.targetOf(context))
    }

    private class FakeDiffContext(private val project: Project) : DiffContext() {

        override fun getProject(): Project = project

        override fun isWindowFocused(): Boolean = true

        override fun isFocusedInWindow(): Boolean = true

        override fun requestFocusInWindow() = Unit
    }
}
