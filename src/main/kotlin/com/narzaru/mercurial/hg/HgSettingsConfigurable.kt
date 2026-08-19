package com.narzaru.mercurial.hg

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.panel
import java.nio.charset.Charset
import javax.swing.JCheckBox
import javax.swing.JComponent

/** Settings → Tools → Mercurial Port. */
class HgSettingsConfigurable : Configurable {

    private val encodingCombo = ComboBox(HgSettings.suggestedEncodings.toTypedArray()).apply {
        isEditable = true // можно вписать любую поддерживаемую кодировку
    }

    private val sharedTabCheck = JCheckBox("Show Hg Changes and Hg File History diffs in one tab")

    override fun getDisplayName() = "Mercurial Port"

    /**
     * UI DSL, а не FormBuilder: `JBLabel` с `<html>` без заданной ширины раскладывается
     * в одну строку и уезжает за границу окна настроек. `text(...)` переносит по словам.
     */
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
            cell(sharedTabCheck)
                .comment("Clear this and each tool window keeps a diff tab of its own (still one per window)")
        }
    }

    private fun selectedEncoding(): String = (encodingCombo.editor.item as? String)?.trim().orEmpty()

    override fun isModified(): Boolean =
        selectedEncoding() != HgSettings.fallbackEncoding || sharedTabCheck.isSelected != HgSettings.shareDiffTab

    override fun apply() {
        HgSettings.shareDiffTab = sharedTabCheck.isSelected
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
        sharedTabCheck.isSelected = HgSettings.shareDiffTab
    }
}
