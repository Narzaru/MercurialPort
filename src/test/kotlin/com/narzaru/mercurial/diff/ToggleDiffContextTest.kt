package com.narzaru.mercurial.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ToggleDiffContextTest : BasePlatformTestCase() {

    fun `test the editor inside a diff tab shows the file it compares`() {
        val diffFile = HgDiffVirtualFile(SimpleDiffRequestChain(emptyList()), "a.txt", "/repo/a.txt")
        val diffContext = FakeDiffContext(project).also { it.putUserData(ToggleDiffContext.DIFF_FILE, diffFile) }

        val target = ToggleDiffContext.targetOf(editorInsideDiff(diffContext))

        assertEquals(ToggleDiffTarget.ShowFile("/repo/a.txt"), target)
    }

    fun `test the editor inside a diff tab finds the tab`() {
        val diffFile = HgDiffVirtualFile(SimpleDiffRequestChain(emptyList()), "a.txt", "/repo/a.txt")
        val diffContext = FakeDiffContext(project).also { it.putUserData(ToggleDiffContext.DIFF_FILE, diffFile) }

        assertSame(diffFile, ToggleDiffContext.diffFileOf(editorInsideDiff(diffContext)))
    }

    fun `test a diff that is not ours is not taken for a diff tab`() {
        assertNull(ToggleDiffContext.diffFileOf(editorInsideDiff(FakeDiffContext(project))))
    }

    private fun editorInsideDiff(diffContext: DiffContext): DataContext {
        val comparedFile = myFixture.configureByText("a.txt", "text").virtualFile
        return SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.VIRTUAL_FILE, comparedFile)
            .add(DiffDataKeys.DIFF_CONTEXT, diffContext)
            .build()
    }

    private class FakeDiffContext(private val project: Project) : DiffContext() {

        override fun getProject(): Project = project

        override fun isWindowFocused(): Boolean = true

        override fun isFocusedInWindow(): Boolean = true

        override fun requestFocusInWindow() = Unit
    }
}
