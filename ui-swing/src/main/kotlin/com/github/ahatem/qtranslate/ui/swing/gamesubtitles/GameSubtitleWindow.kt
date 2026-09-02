package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.ui.swing.livelens.WindowsClickThrough
import java.awt.AlphaComposite
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
import kotlin.math.roundToInt
import kotlin.math.sin

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

    fun showTranslation(text: String, replaceLatest: Boolean) {
        captionPanel.appendCaption(text, replaceLatest)
    }

    fun resetTranslations() {
        captionPanel.reset()
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

private data class CaptionRenderLayout(
    val line: GameSubtitleVisualLine,
    val wrappedLines: List<String>,
    val boxWidth: Int,
    val boxHeight: Int,
    val targetY: Int
)

private class CaptionPanel(private val loadingText: String) : JComponent() {
    private val timeline = GameSubtitleTimeline()
    private val renderedY = mutableMapOf<Long, Float>()
    private var loading = false
    private var pulsePhase = 0f
    private val timer = Timer(ANIMATION_FRAME_MS) {
        pulsePhase = (pulsePhase + PULSE_STEP) % FULL_CIRCLE
        repaint()
    }

    init {
        isOpaque = false
        timer.start()
    }

    fun appendCaption(value: String, replaceLatest: Boolean) {
        timeline.append(value, System.currentTimeMillis(), replaceLatest)
        repaint()
    }

    fun reset() {
        timeline.clear()
        renderedY.clear()
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
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val now = System.currentTimeMillis()
        val visibleLines = timeline.snapshot(now)
        if (visibleLines.isEmpty()) {
            renderedY.clear()
            if (loading) paintLoadingPlaceholder(g)
            g.dispose()
            return
        }

        val fontSize = (height * 0.075f).coerceIn(18f, 32f)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize.toInt())
        val metrics = g.fontMetrics
        val maxTextWidth = (width - 64).coerceAtLeast(120)
        val lineHeight = metrics.height
        val layouts = buildLayouts(visibleLines, metrics, maxTextWidth, lineHeight)
        val visibleIds = visibleLines.mapTo(mutableSetOf()) { line -> line.id }
        renderedY.keys.retainAll(visibleIds)

        layouts.asReversed().forEachIndexed { index, layout ->
            val currentY = renderedY[layout.line.id] ?: layout.targetY.toFloat()
            val nextY = currentY + (layout.targetY - currentY) * MOVE_EASING
            renderedY[layout.line.id] = nextY
            val isNewest = index == layouts.lastIndex
            paintCaption(g, layout, nextY.roundToInt(), lineHeight, isNewest)
        }
        g.dispose()
    }

    private fun buildLayouts(
        lines: List<GameSubtitleVisualLine>,
        metrics: FontMetrics,
        maxTextWidth: Int,
        lineHeight: Int
    ): List<CaptionRenderLayout> {
        val result = mutableListOf<CaptionRenderLayout>()
        var bottom = height - BOTTOM_INSET
        lines.asReversed().forEach { line ->
            val wrapped = wrapCaption(line.text, metrics, maxTextWidth, MAX_WRAPPED_LINES)
            if (wrapped.isEmpty()) return@forEach
            val textWidth = wrapped.maxOfOrNull(metrics::stringWidth).orEmptyWidth()
            val boxWidth = (textWidth + HORIZONTAL_PADDING).coerceAtMost(width - 20)
            val boxHeight = lineHeight * wrapped.size + VERTICAL_PADDING
            val targetY = bottom - boxHeight
            if (targetY < TOP_INSET && result.isNotEmpty()) return@forEach
            result += CaptionRenderLayout(
                line = line,
                wrappedLines = wrapped,
                boxWidth = boxWidth,
                boxHeight = boxHeight,
                targetY = targetY.coerceAtLeast(TOP_INSET)
            )
            bottom = targetY - CAPTION_GAP
        }
        return result
    }

    private fun paintCaption(
        g: Graphics2D,
        layout: CaptionRenderLayout,
        y: Int,
        lineHeight: Int,
        isNewest: Boolean
    ) {
        val opacity = layout.line.opacity.coerceIn(MIN_VISIBLE_OPACITY, 1f)
        g.composite = AlphaComposite.SrcOver.derive(opacity)
        val x = (width - layout.boxWidth) / 2
        g.color = Color(3, 12, 15, 218)
        g.fillRoundRect(x, y, layout.boxWidth, layout.boxHeight, 18, 18)
        g.color = Color(54, 207, 184, 205)
        g.fillRoundRect(x + 1, y + 1, layout.boxWidth - 2, 3, 12, 12)

        layout.wrappedLines.forEachIndexed { index, text ->
            val lineWidth = g.fontMetrics.stringWidth(text)
            val textX = (width - lineWidth) / 2
            val baseline = y + 13 + g.fontMetrics.ascent + index * lineHeight
            g.color = Color(0, 0, 0, 190)
            g.drawString(text, textX + 2, baseline + 2)
            g.color = Color.WHITE
            g.drawString(text, textX, baseline)
        }

        if (loading && isNewest) {
            val pulseAlpha = (145 + 100 * ((sin(pulsePhase.toDouble()) + 1.0) / 2.0)).toInt()
            g.color = Color(75, 231, 204, pulseAlpha)
            g.fillOval(x + layout.boxWidth - 17, y + 9, 7, 7)
        }
        g.composite = AlphaComposite.SrcOver
    }

    private fun paintLoadingPlaceholder(g: Graphics2D) {
        val fontSize = (height * 0.06f).coerceIn(16f, 24f)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize.toInt())
        val metrics = g.fontMetrics
        val textWidth = metrics.stringWidth(loadingText)
        val boxWidth = (textWidth + 40).coerceAtMost(width - 20)
        val boxHeight = metrics.height + 20
        val x = (width - boxWidth) / 2
        val y = (height - boxHeight - BOTTOM_INSET).coerceAtLeast(TOP_INSET)
        val pulseAlpha = (145 + 60 * ((sin(pulsePhase.toDouble()) + 1.0) / 2.0)).toInt()
        g.color = Color(3, 12, 15, pulseAlpha)
        g.fillRoundRect(x, y, boxWidth, boxHeight, 16, 16)
        g.color = Color(190, 216, 212, pulseAlpha.coerceAtMost(220))
        g.drawString(loadingText, (width - textWidth) / 2, y + 10 + metrics.ascent)
    }

    private companion object {
        const val ANIMATION_FRAME_MS = 40
        const val PULSE_STEP = 0.18f
        const val FULL_CIRCLE = 6.283_185f
        const val MOVE_EASING = 0.2f
        const val MIN_VISIBLE_OPACITY = 0.02f
        const val MAX_WRAPPED_LINES = 2
        const val HORIZONTAL_PADDING = 36
        const val VERTICAL_PADDING = 22
        const val CAPTION_GAP = 10
        const val TOP_INSET = 8
        const val BOTTOM_INSET = 14
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
