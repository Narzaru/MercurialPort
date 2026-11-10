package com.narzaru.mercurial.changes

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.DocumentAdapter
import java.awt.event.ActionListener
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import javax.swing.ComboBoxEditor
import javax.swing.JTextField
import javax.swing.border.Border
import javax.swing.event.DocumentEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.UIResource

class BranchField(
    private val onEdited: () -> Unit,
    private val onApply: () -> Unit,
    private val onChoicesNeeded: () -> Unit
) : ComboBox<String>(CollectionComboBoxModel<String>()) {

    private val choicesModel = model as CollectionComboBoxModel<String>

    private val textField = BranchTextField()

    private var choices: List<String> = emptyList()

    private var adjusting = false

    private var showingSuggestions = false

    private var filtered = false

    private var awaitingChoices = false

    var text: String
        get() = textField.text.orEmpty()
        set(value) = adjust {
            choicesModel.selectedItem = value
            if (textField.text != value) textField.text = value
        }

    init {
        isEditable = true
        setEditor(BranchEditor(textField))
        textField.keyHandler = ::handleKey
        textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onTextChanged()
        })
        textField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                if (e.isTemporary) return
                awaitingChoices = false
                onApply()
            }
        })
        addActionListener { if (!adjusting) onApply() }
        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                onChoicesNeeded()
                if (!showingSuggestions) {
                    filtered = false
                    fill(choices)
                }
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) = Unit

            override fun popupMenuCanceled(e: PopupMenuEvent) = Unit
        })
    }

    fun setChoices(names: List<String>) {
        val changed = names != choices
        choices = names
        when {
            awaitingChoices && textField.isFocusOwner -> {
                awaitingChoices = false
                showSuggestions()
            }
            changed && isPopupVisible -> if (filtered) showSuggestions() else reopen(choices)
        }
    }

    private fun onTextChanged() {
        if (adjusting) return
        onEdited()
        if (textField.typing) suggest()
    }

    private fun suggest() {
        onChoicesNeeded()
        if (choices.isEmpty()) {
            awaitingChoices = true
            if (isPopupVisible) hidePopup()
        } else {
            showSuggestions()
        }
    }

    private fun showSuggestions() {
        val suggestions = BranchSuggestions.matching(choices, text)
        if (suggestions.isEmpty()) {
            if (isPopupVisible) hidePopup()
            return
        }
        filtered = true
        reopen(suggestions)
    }

    private fun reopen(items: List<String>) {
        fill(items)
        showingSuggestions = true
        try {
            if (isPopupVisible) hidePopup()
            showPopup()
        } finally {
            showingSuggestions = false
        }
        popup?.list?.clearSelection()
    }

    private fun fill(items: List<String>) = adjust {
        choicesModel.selectedItem = text
        if (choicesModel.items != items) choicesModel.replaceAll(items)
    }

    private fun handleKey(e: KeyEvent): Boolean {
        if (e.modifiersEx != 0) return false
        return when (e.keyCode) {
            KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN -> {
                if (isPopupVisible) moveHighlight(down = true) else suggest()
                true
            }
            KeyEvent.VK_UP, KeyEvent.VK_KP_UP -> {
                if (isPopupVisible) moveHighlight(down = false)
                true
            }
            KeyEvent.VK_ENTER -> isPopupVisible.also { if (it) applyHighlighted() }
            KeyEvent.VK_ESCAPE -> isPopupVisible.also { if (it) hidePopup() }
            else -> false
        }
    }

    private fun moveHighlight(down: Boolean) {
        val list = popup?.list ?: return
        val index = BranchSuggestions.moved(list.selectedIndex, list.model.size, down)
        if (index == BranchSuggestions.NONE) {
            list.clearSelection()
        } else {
            list.selectedIndex = index
            list.ensureIndexIsVisible(index)
        }
    }

    private fun applyHighlighted() {
        val picked = popup?.list?.selectedValue as? String
        hidePopup()
        if (picked != null) text = picked
        onApply()
    }

    private inline fun adjust(change: () -> Unit) {
        val outer = adjusting
        adjusting = true
        try {
            change()
        } finally {
            adjusting = outer
        }
    }

    private class BranchEditor(private val field: JTextField) : ComboBoxEditor {

        override fun getEditorComponent(): JTextField = field

        override fun getItem(): Any = field.text.orEmpty()

        override fun setItem(anObject: Any?) {
            val value = anObject?.toString().orEmpty()
            if (value != field.text) field.text = value
        }

        override fun selectAll() {
            field.selectAll()
            field.requestFocusInWindow()
        }

        override fun addActionListener(l: ActionListener) = field.addActionListener(l)

        override fun removeActionListener(l: ActionListener) = field.removeActionListener(l)
    }

    private class BranchTextField : JTextField("", EDITOR_COLUMNS) {

        var keyHandler: ((KeyEvent) -> Boolean)? = null

        var typing = false
            private set

        override fun processKeyEvent(e: KeyEvent) {
            if (e.id == KeyEvent.KEY_PRESSED && keyHandler?.invoke(e) == true) {
                e.consume()
                return
            }
            typing = true
            try {
                super.processKeyEvent(e)
            } finally {
                typing = false
            }
        }

        override fun setBorder(border: Border?) {
            if (border !is UIResource) super.setBorder(border)
        }
    }

    private companion object {
        const val EDITOR_COLUMNS = 9
    }
}
