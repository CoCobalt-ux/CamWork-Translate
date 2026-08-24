package com.github.ahatem.qtranslate.ui.swing.main.languagebar

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.LanguageComboBox
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import com.github.ahatem.qtranslate.ui.swing.main.layout.ResponsiveUi
import net.miginfocom.swing.MigLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JButton
import javax.swing.JPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

class LanguageSelectionBar(
    private val iconManager: IconManager,
    private val localizer: LocalizationManager,
    private val onClear: () -> Unit,
    private val onSourceLanguageSelected: (LanguageCode) -> Unit,
    private val onSwap: () -> Unit,
    private val onTargetLanguageSelected: (LanguageCode) -> Unit,
    private val onTranslate: () -> Unit,
    private val onCancel: () -> Unit = {}
) : JPanel(MigLayout("insets 0, fillx, hidemode 3, gap 4", "[][grow,fill][][grow,fill][]", "[]")),
    Renderable<LanguageSelectionBarState> {

    private val clearButton = createButtonWithIcon(iconManager, Icons.DELETE, 16)
    private val sourceLanguageComboBox = LanguageComboBox(
        onLanguageSelected = { lang -> onSourceLanguageSelected(lang) },
        localizer = localizer
    )
    private val swapButton = createButtonWithIcon(iconManager, Icons.SWAP, 16)
    private val targetLanguageComboBox = LanguageComboBox(
        onLanguageSelected = { lang -> onTargetLanguageSelected(lang) },
        localizer = localizer
    )
    private val translateButton = JButton(iconManager.getIcon(Icons.TRANSLATE, 16, 16))

    // Single listener that delegates to the correct callback depending on mode.
    private var isInCancelMode = false
    private var actionText = ""
    private var isCompact = false

    init {
        clearButton.addActionListener { onClear() }
        swapButton.addActionListener { onSwap() }
        translateButton.addActionListener { if (isInCancelMode) onCancel() else onTranslate() }

        sourceLanguageComboBox.minimumSize = Dimension(0, sourceLanguageComboBox.preferredSize.height)
        targetLanguageComboBox.minimumSize = Dimension(0, targetLanguageComboBox.preferredSize.height)

        add(clearButton)
        add(sourceLanguageComboBox, "growx, pushx, wmin 0")
        add(swapButton)
        add(targetLanguageComboBox, "growx, pushx, wmin 0")
        add(translateButton)

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = updateCompactMode()
        })
    }

    override fun render(state: LanguageSelectionBarState) {
        clearButton.isEnabled = !state.isLoading && state.canClear
        clearButton.toolTipText = state.strings.clearTooltip

        swapButton.isEnabled = !state.isLoading && state.canSwap
        swapButton.toolTipText = state.strings.swapTooltip

        // While loading: morph the Translate button into a Cancel button.
        // The single action listener checks isInCancelMode to route correctly.
        isInCancelMode = state.isLoading
        translateButton.isEnabled = true
        if (state.isLoading) {
            actionText = state.strings.cancelButtonText
        } else {
            actionText = state.strings.translateButtonText
        }
        translateButton.toolTipText = actionText
        updateCompactMode(force = true)

        sourceLanguageComboBox.render(
            availableLanguages = state.allSourceLanguages,
            selectedLanguage = state.selectedSourceLanguage,
            autoDetectedLanguage = state.detectedSourceLanguage,
            isEnabled = !state.isLoading
        )

        targetLanguageComboBox.render(
            availableLanguages = state.allTargetLanguages,
            selectedLanguage = state.selectedTargetLanguage,
            autoDetectedLanguage = null,
            isEnabled = !state.isLoading
        )
    }

    private fun updateCompactMode(force: Boolean = false) {
        val compact = ResponsiveUi.shouldUseCompactToolbar(width)
        if (!force && compact == isCompact) return
        isCompact = compact
        sourceLanguageComboBox.setCompactMode(compact)
        targetLanguageComboBox.setCompactMode(compact)
        translateButton.text = actionText.takeUnless { compact }
        revalidate()
        repaint()
    }
}
