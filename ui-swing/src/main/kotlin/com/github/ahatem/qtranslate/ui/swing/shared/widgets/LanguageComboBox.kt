package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import com.formdev.flatlaf.util.UIScale
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Dimension
import java.awt.event.ActionListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.BasicComboPopup

/**
 * @param compact shows only the language code on the closed control, with full names still in the
 *   list. For places with no room for "Chinese (Simplified)" on screen at all — the quick
 *   translate popup, which can be narrower than one such name. The same shape as the translator
 *   picker beside it, which shows an icon closed and full names open.
 */
class LanguageComboBox(
    private val onLanguageSelected: (language: LanguageCode) -> Unit,
    private val localizer: LocalizationManager,
    compact: Boolean = false
) : JComboBox<LanguageCode>() {

    private var isRendering = false
    private val renderSequence = AtomicLong()
    private var currentLanguages: List<LanguageCode> = emptyList()
    private var compactMode: Boolean = compact
    private val languageRenderer = LanguageRenderer(this, localizer) { compactMode }

    private val actionListener = ActionListener {
        if (!isRendering) {
            (selectedItem as? LanguageCode)?.let { onLanguageSelected(it) }
        }
    }

    private val searchStringBuilder = StringBuilder()
    private val searchResetTimer = Timer(500) {
        searchStringBuilder.clear()
    }.apply {
        isRepeats = false
    }

    init {
        renderer = languageRenderer
        addActionListener(actionListener)

        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                if (e.keyChar.isLetterOrDigit()) {
                    handleKeyTyped(e.keyChar)
                }
            }
        })

        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = widenPopupToFitNames()
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = Unit
            override fun popupMenuCanceled(event: PopupMenuEvent) = Unit
        })

        if (compactMode) {
            // A combo sizes itself to its widest item, and the items are full language names, so
            // it would still take the width of "Chinese (Simplified)" while displaying "ZH-CN".
            // The prototype is the widest code rather than the widest name.
            prototypeDisplayValue = LanguageCode(WIDEST_CODE)

        }
    }

    /** Меняет только представление закрытого списка; модель и выбранный язык сохраняются. */
    fun setCompactMode(enabled: Boolean) {
        if (compactMode == enabled) return
        compactMode = enabled
        prototypeDisplayValue = if (enabled) LanguageCode(WIDEST_CODE) else null
        revalidate()
        repaint()
    }

    /**
     * Gives the open list enough width for the longest name in it.
     *
     * Measured rather than guessed, because the names are localized and translated: whatever is
     * longest in English is not what is longest in Arabic or Japanese.
     */
    private fun widenPopupToFitNames() {
        val popup = getAccessibleContext()?.getAccessibleChild(0) as? BasicComboPopup ?: return
        val scrollPane = popup.getComponent(0) as? JScrollPane ?: return

        val autoDetectLabel = localizer.getString("common.auto_detect")
        val metrics = getFontMetrics(font)
        val widest = (0 until model.size).maxOfOrNull { index ->
            metrics.stringWidth(model.getElementAt(index).getDisplayName(autoDetectLabel = autoDetectLabel))
        } ?: return

        val padding = UIScale.scale(POPUP_PADDING)
        scrollPane.preferredSize = Dimension(
            maxOf(widest + padding, width),
            scrollPane.preferredSize.height
        )
    }

    private fun handleKeyTyped(key: Char) {
        searchResetTimer.stop()
        searchStringBuilder.append(key)
        val searchString = searchStringBuilder.toString()

        val model = this.model
        for (i in 0 until model.size) {
            val item = model.getElementAt(i)
            val displayString = item?.getDisplayName(
                autoDetectLabel = localizer.getString("common.auto_detect")
            ) ?: item.toString()

            if (displayString.startsWith(searchString, ignoreCase = true)) {
                this.selectedIndex = i
                searchResetTimer.start()
                return
            }
        }

        searchStringBuilder.clear()
    }

    fun render(
        availableLanguages: List<LanguageCode>,
        selectedLanguage: LanguageCode?,
        autoDetectedLanguage: LanguageCode?,
        isEnabled: Boolean
    ) {
        val sequence = renderSequence.incrementAndGet()
        val renderOnUi = renderOnUi@{
            if (sequence != renderSequence.get()) return@renderOnUi
            isRendering = true
            try {
                putClientProperty("autoDetectedLanguage", autoDetectedLanguage)
                // Фильтр избранных и временная недоступность сервиса не меняют выбор пользователя.
                val visibleLanguages = if (selectedLanguage != null && selectedLanguage !in availableLanguages) {
                    availableLanguages + selectedLanguage
                } else {
                    availableLanguages
                }
                if (currentLanguages != visibleLanguages) {
                    model = DefaultComboBoxModel(visibleLanguages.toTypedArray())
                    currentLanguages = visibleLanguages
                }
                this.selectedItem = selectedLanguage
                this.isEnabled = isEnabled
            } finally {
                isRendering = false
            }
        }
        if (SwingUtilities.isEventDispatchThread()) renderOnUi() else SwingUtilities.invokeLater(renderOnUi)
    }

    private class LanguageRenderer(
        private val comboBox: JComboBox<LanguageCode>,
        private val localizer: LocalizationManager,
        private val isCompact: () -> Boolean
    ) : DefaultListCellRenderer() {

        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is LanguageCode) {
                val autoDetectLabel = localizer.getString("common.auto_detect")
                val autoDetectedLanguage = comboBox.getClientProperty("autoDetectedLanguage") as? LanguageCode

                // index == -1 is the closed control; anything else is a row in the open list.
                val isClosedControl = index == -1

                val fullName: String =
                    if (isClosedControl && autoDetectedLanguage != null && value == LanguageCode.AUTO) {
                        "$autoDetectLabel (${autoDetectedLanguage.getDisplayName()})"
                    } else {
                        value.getDisplayName(autoDetectLabel = autoDetectLabel)
                    }

                if (isCompact() && isClosedControl) {
                    // AUTO — выбранный режим; результат определения раскрывается отдельно в подсказке.
                    text = value.tag.uppercase()
                    // The name the code stands for, since the code alone is not always obvious.
                    toolTipText = fullName
                    comboBox.toolTipText = fullName
                    componentOrientation = ComponentOrientation.LEFT_TO_RIGHT
                } else {
                    text = if (isClosedControl && comboBox.width > 0) {
                        val availableWidth = (comboBox.width - UIScale.scale(CLOSED_CONTROL_PADDING)).coerceAtLeast(0)
                        com.github.ahatem.qtranslate.ui.swing.main.layout.ResponsiveUi.elideText(
                            fullName,
                            availableWidth,
                            comboBox.getFontMetrics(comboBox.font)::stringWidth
                        )
                    } else fullName
                    toolTipText = null
                    if (isClosedControl) comboBox.toolTipText = fullName
                    componentOrientation = if (fullName.isRTL()) ComponentOrientation.RIGHT_TO_LEFT
                    else ComponentOrientation.LEFT_TO_RIGHT
                }
            }
            return this
        }
    }

    private companion object {
        /** Sized against the longest code in use rather than the longest name. */
        const val WIDEST_CODE = "zh-CN"

        /** Room for the list's own borders and scrollbar beside the widest name. */
        const val POPUP_PADDING = 40
        const val CLOSED_CONTROL_PADDING = 38
    }
}
