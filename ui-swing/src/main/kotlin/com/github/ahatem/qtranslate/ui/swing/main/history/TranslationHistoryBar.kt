package com.github.ahatem.qtranslate.ui.swing.main.history

import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.ComponentOrientation
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.main.layout.ResponsiveUi
import net.miginfocom.swing.MigLayout
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

class TranslationHistoryBar(
    private val iconManager: IconManager,
    private val onBackward: () -> Unit,
    private val onForward: () -> Unit,
    private val onImageTranslate: () -> Unit,
    private val onDocumentTranslate: () -> Unit,
) : JPanel(MigLayout("insets 0, fillx, hidemode 3, gap 2", "[][][grow,fill][][]", "[]")),
    Renderable<TranslationHistoryBarState> {

    private val backwardButton = createButtonWithIcon(iconManager, Icons.NAV_BACK, 16)
    private val forwardButton = createButtonWithIcon(iconManager, Icons.NAV_FORWARD, 16)
    private val imageTranslateButton = createButtonWithIcon(iconManager, Icons.OCR, 16)
    private val documentTranslateButton = createButtonWithIcon(iconManager, Icons.DOCUMENT, 16)

    private val statusLabel = ElidingStatusLabel().apply {
        border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
    }

    init {
        backwardButton.addActionListener { onBackward() }
        forwardButton.addActionListener { onForward() }
        imageTranslateButton.addActionListener { onImageTranslate() }
        documentTranslateButton.addActionListener { onDocumentTranslate() }

        add(backwardButton)
        add(forwardButton)
        add(statusLabel, "growx, pushx, wmin 0")
        add(imageTranslateButton)
        add(documentTranslateButton)
    }

    override fun applyComponentOrientation(orientation: ComponentOrientation) {
        super.applyComponentOrientation(orientation)
        revalidate()
        repaint()

        val isRtl = orientation == java.awt.ComponentOrientation.RIGHT_TO_LEFT
        backwardButton.icon = iconManager.getIcon(
            if (isRtl) Icons.NAV_FORWARD else Icons.NAV_BACK,
            16, 16
        )
        forwardButton.icon = iconManager.getIcon(
            if (isRtl) Icons.NAV_BACK else Icons.NAV_FORWARD,
            16, 16
        )
    }

    override fun render(state: TranslationHistoryBarState) {
        statusLabel.setFullText(state.statusText)

        backwardButton.isEnabled = !state.isLoading && state.canGoBackward
        forwardButton.isEnabled = !state.isLoading && state.canGoForward
        imageTranslateButton.isEnabled = !state.isLoading
        documentTranslateButton.isEnabled = !state.isLoading

        backwardButton.toolTipText = state.strings.backwardTooltip
        forwardButton.toolTipText = state.strings.forwardTooltip
        imageTranslateButton.toolTipText = state.strings.imageTranslateTooltip
        documentTranslateButton.toolTipText = state.strings.documentTranslateTooltip
    }

    private class ElidingStatusLabel : JLabel() {
        private var fullText: String = ""

        init {
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = refreshText()
            })
        }

        fun setFullText(value: String) {
            if (fullText == value) return
            fullText = value
            refreshText()
        }

        private fun refreshText() {
            val insetsWidth = insets.left + insets.right
            val availableWidth = width - insetsWidth
            val displayed = if (availableWidth > 0) {
                ResponsiveUi.elideText(fullText, availableWidth, getFontMetrics(font)::stringWidth)
            } else {
                fullText
            }
            if (text != displayed) text = displayed
            toolTipText = fullText.takeIf { displayed != fullText }
        }

        override fun getMinimumSize(): Dimension = Dimension(0, super.getMinimumSize().height)

        override fun getPreferredSize(): Dimension = super.getPreferredSize().let { preferred ->
            Dimension(preferred.width.coerceAtMost(UIScale.scale(MAX_PREFERRED_WIDTH)), preferred.height)
        }

        private companion object {
            const val MAX_PREFERRED_WIDTH = 320
        }
    }
}
