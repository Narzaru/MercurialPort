package com.github.narzaru.hgrider.hg

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.panel
import java.nio.charset.Charset
import javax.swing.JCheckBox
import javax.swing.JComponent

/** Settings → Tools → hgrider. */
class HgSettingsConfigurable : Configurable {

    private val encodingCombo = ComboBox(HgSettings.suggestedEncodings.toTypedArray()).apply {
        isEditable = true // можно вписать любую поддерживаемую кодировку
    }

    private val sharedTabCheck = JCheckBox("Показывать дифф Hg Changes и Hg File History в одной вкладке")

    override fun getDisplayName() = "hgrider (Mercurial)"

    /**
     * UI DSL, а не FormBuilder: `JBLabel` с `<html>` без заданной ширины раскладывается
     * в одну строку и уезжает за границу окна настроек. `text(...)` переносит по словам.
     */
    override fun createComponent(): JComponent = panel {
        row {
            text(
                "Вывод <code>hg</code> сначала читается как UTF-8. Если строка не является корректным " +
                    "UTF-8 (обычно так и бывает с сообщениями коммитов, набранными в системной кодировке), " +
                    "она декодируется указанной ниже кодировкой."
            )
        }
        row("Кодировка коммитов и вывода hg:") {
            cell(encodingCombo)
                .comment("Кодировка системы: <b>${HgSettings.systemDefault}</b>")
        }
        row {
            cell(sharedTabCheck)
                .comment("Если снять, у каждого окна будет своя (одна) дифф-вкладка")
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
            throw com.intellij.openapi.options.ConfigurationException("Неизвестная кодировка: $encoding")
        }
        HgSettings.fallbackEncoding = encoding
    }

    override fun reset() {
        encodingCombo.selectedItem = HgSettings.fallbackEncoding
        encodingCombo.editor.item = HgSettings.fallbackEncoding
        sharedTabCheck.isSelected = HgSettings.shareDiffTab
    }
}
