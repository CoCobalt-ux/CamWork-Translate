package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.ui.swing.livelens.WindowsClickThrough
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

internal data class GameSubtitleStrings(
    val title: String,
    val active: String,
    val scanning: String,
    val translating: String,
    val paused: String,
    val failed: String,
    val pause: String,
    val resume: String,
    val changeArea: String,
    val close: String
)

internal enum class GameSubtitleWindowMode { SCANNING, TRANSLATING, PAUSED, ERROR }

/** Два независимых окна: интерактивная панель и полностью кликабельная насквозь строка перевода. */
internal class GameSubtitleWindow(
    private val strings: GameSubtitleStrings,
    private val onTogglePause: () -> Unit,
    private val onChangeArea: () -> Unit,
    private val onClose: () -> Unit
) {
    private val captionPanel = CaptionPanel(strings.translating)
    private val overlay = JWindow().apply {
        background = Color(0, 0, 0, 0)
        isAlwaysOnTop = true
        focusableWindowState = false
        contentPane = captionPanel
    }

    private val statusLabel = JLabel(strings.scanning).apply {
        foreground = Color(132, 231, 214)
        horizontalAlignment = SwingConstants.LEFT
        font = font.deriveFont(Font.BOLD, 12f)
    }
    private val pauseButton = toolbarButton(strings.pause) { onTogglePause() }
    private val control = JWindow().apply {
        isAlwaysOnTop = true
        focusableWindowState = true
        background = Color(0, 0, 0, 0)
        contentPane = createControlPanel()
        pack()
    }

    private var paused = false
    private var captureExclusionEnabled = false

    fun showAt(captureBounds: Rectangle) {
        captionPanel.start()
        overlay.bounds = Rectangle(captureBounds)
        positionControl(captureBounds)
        overlay.isVisible = true
        control.isVisible = true
        WindowsClickThrough.setEnabled(overlay, true)
        captureExclusionEnabled = WindowsClickThrough.setExcludedFromCapture(overlay, true)
        WindowsClickThrough.setExcludedFromCapture(control, true)
        setMode(GameSubtitleWindowMode.SCANNING)
    }

    fun setMode(mode: GameSubtitleWindowMode) {
        paused = mode == GameSubtitleWindowMode.PAUSED
        pauseButton.text = if (paused) strings.resume else strings.pause
        statusLabel.text = when (mode) {
            GameSubtitleWindowMode.SCANNING -> "●  ${strings.active}"
            GameSubtitleWindowMode.TRANSLATING -> "●  ${strings.translating}"
            GameSubtitleWindowMode.PAUSED -> "●  ${strings.paused}"
            GameSubtitleWindowMode.ERROR -> "●  ${strings.failed}"
        }
        statusLabel.foreground = when (mode) {
            GameSubtitleWindowMode.ERROR -> Color(244, 125, 125)
            GameSubtitleWindowMode.PAUSED -> Color(181, 190, 193)
            else -> Color(101, 226, 203)
        }
        captionPanel.setLoading(mode == GameSubtitleWindowMode.TRANSLATING)
    }

    fun showTranslation(text: String) {
        captionPanel.setCaption(text)
    }

    fun clearTranslation() {
        captionPanel.setCaption("")
    }

    /** На старых Windows защита от захвата может отсутствовать; тогда окно скрывается только на время кадра. */
    fun <T> captureWithoutOverlay(action: () -> T): T {
        if (captureExclusionEnabled || !overlay.isVisible) return action()
        onUiAndWait { overlay.isVisible = false }
        Toolkit.getDefaultToolkit().sync()
        return try {
            action()
        } finally {
            onUiAndWait {
                overlay.isVisible = true
                WindowsClickThrough.setEnabled(overlay, true)
            }
        }
    }

    fun dispose() {
        captionPanel.stop()
        WindowsClickThrough.setExcludedFromCapture(overlay, false)
        WindowsClickThrough.setExcludedFromCapture(control, false)
        overlay.dispose()
        control.dispose()
    }

    private fun createControlPanel(): JPanel = object : JPanel() {
        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(11, 27, 30, 242)
            g.fillRoundRect(0, 0, width, height, 14, 14)
            g.color = Color(58, 198, 177, 180)
            g.stroke = BasicStroke(1f)
            g.drawRoundRect(0, 0, width - 1, height - 1, 14, 14)
            g.dispose()
            super.paintComponent(graphics)
        }
    }.apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createEmptyBorder(7, 12, 7, 9)
        add(JLabel(strings.title).apply {
            foreground = Color.WHITE
            font = font.deriveFont(Font.BOLD, 13f)
            alignmentY = Component.CENTER_ALIGNMENT
        })
        add(Box.createHorizontalStrut(12))
        add(statusLabel)
        add(Box.createHorizontalStrut(12))
        add(pauseButton)
        add(Box.createHorizontalStrut(4))
        add(toolbarButton(strings.changeArea) { onChangeArea() })
        add(Box.createHorizontalStrut(4))
        add(toolbarButton("×") { onClose() }.apply {
            preferredSize = Dimension(34, 28)
            toolTipText = strings.close
        })
    }

    private fun toolbarButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = Color(225, 239, 237)
        font = font.deriveFont(Font.PLAIN, 12f)
        isFocusable = false
        addActionListener { action() }
    }

    private fun positionControl(captureBounds: Rectangle) {
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }
            .firstOrNull { it.contains(captureBounds.centerX.toInt(), captureBounds.centerY.toInt()) }
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val preferred = control.preferredSize
        val width = preferred.width.coerceAtMost(screen.width - 16)
        val x = (captureBounds.x + (captureBounds.width - width) / 2)
            .coerceIn(screen.x + 8, screen.x + screen.width - width - 8)
        val above = captureBounds.y - preferred.height - 8
        val y = if (above >= screen.y + 8) above else {
            (captureBounds.y + captureBounds.height + 8)
                .coerceAtMost(screen.y + screen.height - preferred.height - 8)
        }
        control.setBounds(x, y, width, preferred.height)
    }

    private fun onUiAndWait(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }
}

private class CaptionPanel(private val loadingText: String) : JComponent() {
    private var caption = ""
    private var loading = false
    private var pulse = false
    private val timer = Timer(320) {
        pulse = !pulse
        repaint()
    }

    init {
        isOpaque = false
        timer.start()
    }

    fun setCaption(value: String) {
        caption = value.trim()
        repaint()
    }

    fun setLoading(value: Boolean) {
        loading = value
        repaint()
    }

    fun stop() {
        timer.stop()
    }

    fun start() {
        if (!timer.isRunning) timer.start()
    }

    override fun paintComponent(graphics: Graphics) {
        val displayText = caption.ifBlank { if (loading) loadingText else "" }
        if (displayText.isBlank()) return

        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val fontSize = (height * 0.12f).coerceIn(21f, 42f)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize.toInt())
        val metrics = g.fontMetrics
        val maxTextWidth = (width - 64).coerceAtLeast(120)
        val lines = wrapCaption(displayText, metrics, maxTextWidth, 3)
        val lineHeight = metrics.height
        val textWidth = lines.maxOfOrNull(metrics::stringWidth).orEmptyWidth()
        val boxWidth = (textWidth + 36).coerceAtMost(width - 20)
        val boxHeight = lineHeight * lines.size + 24
        val x = (width - boxWidth) / 2
        val y = (height - boxHeight - 14).coerceAtLeast(8)

        g.color = Color(3, 12, 15, 218)
        g.fillRoundRect(x, y, boxWidth, boxHeight, 20, 20)
        g.color = Color(54, 207, 184, 205)
        g.fillRoundRect(x + 1, y + 1, boxWidth - 2, 3, 12, 12)

        lines.forEachIndexed { index, line ->
            val lineWidth = metrics.stringWidth(line)
            val textX = (width - lineWidth) / 2
            val baseline = y + 14 + metrics.ascent + index * lineHeight
            g.color = Color(0, 0, 0, 190)
            g.drawString(line, textX + 2, baseline + 2)
            g.color = Color.WHITE
            g.drawString(line, textX, baseline)
        }

        if (loading && caption.isNotBlank()) {
            g.color = Color(75, 231, 204, if (pulse) 245 else 95)
            g.fillOval(x + boxWidth - 17, y + 9, 7, 7)
        }
        g.dispose()
    }
}

internal fun wrapCaption(
    value: String,
    metrics: FontMetrics,
    maxWidth: Int,
    maxLines: Int
): List<String> {
    if (value.isBlank()) return emptyList()
    val words = value.trim().split(Regex("\\s+"))
    val lines = mutableListOf<String>()
    var current = ""
    for (word in words) {
        val candidate = if (current.isBlank()) word else "$current $word"
        if (metrics.stringWidth(candidate) <= maxWidth || current.isBlank()) {
            current = candidate
            continue
        }
        lines += current
        current = word
        if (lines.size == maxLines - 1) break
    }
    if (current.isNotBlank() && lines.size < maxLines) lines += current

    val consumed = lines.joinToString(" ").length
    if (consumed < value.trim().length && lines.isNotEmpty()) {
        var last = lines.last().trimEnd('.', '…')
        while (last.isNotEmpty() && metrics.stringWidth("$last…") > maxWidth) {
            last = last.dropLast(1)
        }
        lines[lines.lastIndex] = "$last…"
    }
    return lines
}

private fun Int?.orEmptyWidth(): Int = this ?: 0
