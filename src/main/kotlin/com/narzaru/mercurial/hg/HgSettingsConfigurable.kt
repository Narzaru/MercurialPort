package com.narzaru.mercurial.hg

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.panel
import com.narzaru.mercurial.changes.HgChangesService
import com.narzaru.mercurial.model.ChangesetsFragments
import com.narzaru.mercurial.status.HgFileStatusService
import java.nio.charset.Charset
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class HgSettingsConfigurable : Configurable {

    private val encodingCombo = ComboBox(HgSettings.suggestedEncodings.toTypedArray()).apply {
        isEditable = true
    }

    private val statusLetterCheck = JCheckBox("Add the file status to editor tab titles")

    private val ignoreEolCheck = JCheckBox("Ignore line ending changes when counting ± lines")

    private val serverCountSpinner = JSpinner(
        SpinnerNumberModel(
            HgServerPool.DEFAULT_SERVERS,
            HgServerPool.MIN_SERVERS,
            HgServerPool.MAX_SERVERS,
            1
        )
    )

    private val commandServerCheck = JCheckBox("Run read-only hg commands through a command server").apply {
        addActionListener { serverCountSpinner.isEnabled = isSelected }
    }

    private val fragmentsCombo = ComboBox(ChangesetsFragments.entries.toTypedArray()).apply {
        addActionListener { toolTipText = (selectedItem as? ChangesetsFragments)?.hint }
    }

    private val widenCheck = JCheckBox("Show a touched fragment whole").apply {
        addActionListener { fragmentsCombo.isEnabled = isSelected }
    }

    override fun getDisplayName() = "Mercurial Port"

    override fun createComponent(): JComponent = panel {
        row {
            text(
                "Output of <code>hg</code> is read as UTF-8 first. A line that is not valid UTF-8 — " +
                    "which is what commit messages typed in the system codepage look like — is decoded " +
                    "with the encoding below."
            )
        }
        row("Encoding of commit messages and hg output:") {
            cell(encodingCombo)
                .comment("System encoding: <b>${HgSettings.systemDefault}</b>")
        }
        row {
            cell(statusLetterCheck)
                .comment("<code>Foo.cs [M]</code> — the letter follows the Hg Changes comparison mode")
        }
        row {
            cell(ignoreEolCheck)
                .comment(
                    "Counts ± with <code>hg diff --ignore-space-at-eol</code>. A file saved with LF " +
                        "instead of CRLF otherwise reads as rewritten whole, which is noise rather " +
                        "than a change — and a review server does not count it either."
                )
        }
        row {
            cell(commandServerCheck)
                .comment(
                    "Keeps a few <code>hg serve --cmdserver pipe</code> processes alive per repository " +
                        "and sends queries to them, so that loading a branch does not pay for starting " +
                        "<code>hg</code> over and over. Commands that write are always run on their own, " +
                        "and a query falls back to a plain run whenever a server is unavailable."
                )
        }
        row("Server processes per repository:") {
            cell(serverCountSpinner)
                .comment(
                    "One process answers one query at a time, so queries that would have run side by " +
                        "side queue up on it — still far quicker than starting <code>hg</code> for each " +
                        "of them. Raise this to trade about 45 MB per process for that parallelism."
                )
        }
        group("Changesets Diff") {
            row {
                text(
                    "In the <b>Changesets</b> mode the left side of a diff is the head with the " +
                        "branch's own patches rolled back out of it, so that what a merge brought " +
                        "into the file stays out of the diff."
                )
            }
            row {
                cell(widenCheck)
                    .comment(
                        "A fragment the branch touched is shown whole, lines a merge brought into " +
                            "the same place included — the way a review server shows it. Clear this " +
                            "and only the branch's own lines are left: the narrowest diff, but a " +
                            "change can end up shown without the code it was written against."
                    )
            }
            row("Fragments are counted by:") {
                cell(fragmentsCombo)
                    .comment("Where a change ends and untouched code begins is this engine's call")
            }
        }
    }

    private fun selectedEncoding(): String = (encodingCombo.editor.item as? String)?.trim().orEmpty()

    private fun selectedServerCount(): Int =
        HgSettings.boundCount((serverCountSpinner.value as? Number)?.toInt() ?: HgSettings.commandServerCount)

    private fun selectedFragments(): ChangesetsFragments =
        fragmentsCombo.selectedItem as? ChangesetsFragments ?: HgSettings.changesetsFragments

    override fun isModified(): Boolean =
        selectedEncoding() != HgSettings.fallbackEncoding ||
            statusLetterCheck.isSelected != HgSettings.statusInTabs ||
            ignoreEolCheck.isSelected != HgSettings.ignoreEolChanges ||
            commandServerCheck.isSelected != HgSettings.useCommandServer ||
            selectedServerCount() != HgSettings.commandServerCount ||
            widenCheck.isSelected != HgSettings.widenToFragments ||
            selectedFragments() != HgSettings.changesetsFragments

    override fun apply() {
        val statusChanged = statusLetterCheck.isSelected != HgSettings.statusInTabs
        val countingChanged = ignoreEolCheck.isSelected != HgSettings.ignoreEolChanges
        val changesetsChanged = widenCheck.isSelected != HgSettings.widenToFragments ||
            selectedFragments() != HgSettings.changesetsFragments

        if (commandServerCheck.isSelected != HgSettings.useCommandServer) {
            HgSettings.useCommandServer = commandServerCheck.isSelected
            if (!commandServerCheck.isSelected) HgCommandServers.getInstanceOrNull()?.shutdownAll()
        }

        if (selectedServerCount() != HgSettings.commandServerCount) {
            HgSettings.commandServerCount = selectedServerCount()
            HgCommandServers.getInstanceOrNull()?.applySettings()
        }

        HgSettings.statusInTabs = statusLetterCheck.isSelected
        HgSettings.ignoreEolChanges = ignoreEolCheck.isSelected
        HgSettings.widenToFragments = widenCheck.isSelected
        HgSettings.changesetsFragments = selectedFragments()

        if (statusChanged || countingChanged || changesetsChanged) {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                if (statusChanged) project.serviceIfCreated<HgFileStatusService>()?.refreshOpenTabs()
                if (countingChanged || changesetsChanged) {
                    project.serviceIfCreated<HgChangesService>()
                        ?.diffSettingsChanged(changesetsOnly = !countingChanged)
                }
            }
        }

        val encoding = selectedEncoding()
        if (encoding.isEmpty()) return
        if (!runCatching { Charset.isSupported(encoding) }.getOrDefault(false)) {
            throw com.intellij.openapi.options.ConfigurationException("Unknown encoding: $encoding")
        }
        HgSettings.fallbackEncoding = encoding
    }

    override fun reset() {
        encodingCombo.selectedItem = HgSettings.fallbackEncoding
        encodingCombo.editor.item = HgSettings.fallbackEncoding
        statusLetterCheck.isSelected = HgSettings.statusInTabs
        ignoreEolCheck.isSelected = HgSettings.ignoreEolChanges
        commandServerCheck.isSelected = HgSettings.useCommandServer
        serverCountSpinner.value = HgSettings.commandServerCount
        serverCountSpinner.isEnabled = HgSettings.useCommandServer
        widenCheck.isSelected = HgSettings.widenToFragments
        fragmentsCombo.selectedItem = HgSettings.changesetsFragments
        fragmentsCombo.isEnabled = widenCheck.isSelected
        fragmentsCombo.toolTipText = HgSettings.changesetsFragments.hint
    }
}
