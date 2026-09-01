package com.github.ahatem.qtranslate.ui.swing.livelens

import java.awt.BasicStroke
import java.awt.Color
import java.awt.ComponentOrientation
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Frame
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class LiveLensStrings(
    val title: String,
    val setupHint: String,
    val start: String,
    val pause: String,
    val edit: String,
    val close: String,
    val watching: String,
    val reading: String,
    val translating: String,
    val noText: String,
    val failed: String,
    val nextScan: String,
    val newMessage: String,
    val secondsShort: String
)

internal enum class LiveLensMode { SETUP, WATCHING, PAUSED, READING, TRANSLATING, ERROR }

private data class LiveLensCard(
    val id: Long,
    val speaker: String?,
    val sourceText: String,
    val translatedText: String,
    val provider: String,
    val createdAt: Long
)

/** Прозрачная область наблюдения с позиционным переводом поверх исходного текста. */
internal class LiveLensWindow(
    private val strings: LiveLensStrings,
    private val onStart: (Rectangle, Boolean) -> Unit,
    private val onPause: () -> Unit,
    private val onClose: () -> Unit,
    private val onBoundsChanged: (Rectangle) -> Unit
) {
    private val overlay = JWindow()
    private val panel = LensPanel(strings)
    private val controls = JDialog(null as Frame?, strings.title, false)
    private lateinit var startButton: JButton
    private lateinit var pauseButton: JButton
    private lateinit var editButton: JButton
    private var mode = LiveLensMode.SETUP
    private var dragOrigin: Point? = null
    private var windowOrigin: Point? = null
    private var resizeOrigin: Point? = null
    private var resizeSize: Dimension? = null

    init {
        overlay.apply {
            isAlwaysOnTop = true
            background = Color(0, 0, 0, 0)
            contentPane = panel
            minimumSize = Dimension(360, 240)
        }
        installMoveAndResize()
        buildControls()
    }

    fun showSetup(bounds: Rectangle) {
        overlay.bounds = bounds
        mode = LiveLensMode.SETUP
        // Окно можно закрыть и открыть заново: таймеры после dispose обязаны ожить,
        // иначе повторный сеанс копит карточки, которые больше никогда не исчезают.
        panel.resumeTimers()
        panel.clearCards()
        panel.setMode(mode, strings.setupHint)
        overlay.focusableWindowState = true
        overlay.isVisible = true
        WindowsClickThrough.setEnabled(overlay, false)
        controls.isVisible = true
        updateControls()
        positionControls()
        overlay.toFront()
    }

    fun beginWatching(clearCards: Boolean, calibrating: Boolean) {
        if (clearCards) panel.clearCards()
        if (calibrating) setMode(LiveLensMode.READING, strings.reading)
        else setMode(LiveLensMode.WATCHING, strings.watching)
    }

    fun setMode(newMode: LiveLensMode, message: String = defaultMessage(newMode)) {
        mode = newMode
        panel.setMode(newMode, message)
        val clickThrough = newMode != LiveLensMode.SETUP
        overlay.focusableWindowState = !clickThrough
        WindowsClickThrough.setEnabled(overlay, clickThrough)
        updateControls()
        positionControls()
    }

    fun snapshotCompleted(nextScanDelayMillis: Long) =
        panel.snapshotCompleted(nextScanDelayMillis)

    fun showTranslation(
        requestId: Long,
        source: LiveLensTextBlock,
        translatedText: String,
        provider: String
    ) {
        panel.addCard(
            id = requestId,
            source = source,
            translatedText = translatedText,
            provider = provider
        )
    }

    fun currentBounds(): Rectangle = Rectangle(overlay.bounds)

    fun currentScanBounds(): Rectangle =
        WindowsClickThrough.nativeBounds(overlay) ?: currentBounds()

    fun dispose() {
        panel.stopAnimation()
        controls.dispose()
        overlay.dispose()
    }

    private fun buildControls() {
        controls.apply {
            isUndecorated = true
            type = Window.Type.UTILITY
            isAlwaysOnTop = true
            focusableWindowState = false
            background = Color(0, 0, 0, 0)
            contentPane = JPanel(FlowLayout(FlowLayout.LEFT, 5, 4)).apply {
                componentOrientation = ComponentOrientation.LEFT_TO_RIGHT
                background = Color(16, 27, 31, 242)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(67, 204, 178, 210)),
                    EmptyBorder(1, 5, 1, 5)
                )
                add(label(strings.title))
                startButton = button(strings.start, primary = true) {
                    if (mode != LiveLensMode.WATCHING && mode != LiveLensMode.TRANSLATING) {
                        val resume = mode == LiveLensMode.PAUSED
                        setMode(if (resume) LiveLensMode.WATCHING else LiveLensMode.READING)
                        onStart(currentBounds(), resume)
                    }
                }
                pauseButton = button(strings.pause) {
                    setMode(LiveLensMode.PAUSED)
                    onPause()
                }
                editButton = button(strings.edit) {
                    onPause()
                    panel.clearCards()
                    setMode(LiveLensMode.SETUP)
                    overlay.toFront()
                }
                add(startButton)
                add(pauseButton)
                add(editButton)
                add(button("×") { onClose() })
            }
            pack()
        }
    }

    private fun updateControls() {
        if (!::startButton.isInitialized) return
        val running = mode == LiveLensMode.WATCHING ||
            mode == LiveLensMode.READING ||
            mode == LiveLensMode.TRANSLATING
        startButton.isEnabled = !running
        pauseButton.isEnabled = running
        editButton.isEnabled = mode != LiveLensMode.SETUP
    }

    private fun label(text: String) = JLabel(text).apply {
        foreground = Color(226, 246, 242)
        font = font.deriveFont(Font.BOLD, 12f)
        border = EmptyBorder(0, 3, 0, 5)
    }

    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = JButton(text).apply {
        isFocusPainted = false
        isOpaque = true
        foreground = if (primary) Color(8, 34, 30) else Color(222, 239, 236)
        background = if (primary) Color(92, 225, 198) else Color(31, 48, 52)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(if (primary) Color(92, 225, 198) else Color(76, 104, 108)),
            EmptyBorder(3, 8, 3, 8)
        )
        margin = Insets(0, 0, 0, 0)
        addActionListener { action() }
    }

    private fun installMoveAndResize() {
        val listener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (mode != LiveLensMode.SETUP) return
                if (e.x >= overlay.width - RESIZE_GRIP && e.y >= overlay.height - RESIZE_GRIP) {
                    resizeOrigin = e.locationOnScreen
                    resizeSize = overlay.size
                } else {
                    dragOrigin = e.locationOnScreen
                    windowOrigin = overlay.location
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                if (mode != LiveLensMode.SETUP) return
                resizeOrigin?.let { origin ->
                    val initial = resizeSize ?: return@let
                    overlay.setSize(
                        max(overlay.minimumSize.width, initial.width + e.xOnScreen - origin.x),
                        max(overlay.minimumSize.height, initial.height + e.yOnScreen - origin.y)
                    )
                    positionControls()
                    return
                }
                val origin = dragOrigin ?: return
                val initial = windowOrigin ?: return
                overlay.setLocation(
                    initial.x + e.xOnScreen - origin.x,
                    initial.y + e.yOnScreen - origin.y
                )
                positionControls()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (dragOrigin != null || resizeOrigin != null) onBoundsChanged(currentBounds())
                dragOrigin = null
                windowOrigin = null
                resizeOrigin = null
                resizeSize = null
            }
        }
        panel.addMouseListener(listener)
        panel.addMouseMotionListener(listener)
    }

    private fun positionControls() {
        controls.pack()
        val x = overlay.x + 8
        val preferredY = overlay.y - controls.height - 6
        controls.setLocation(x, max(0, preferredY))
        controls.toFront()
    }

    private fun defaultMessage(value: LiveLensMode): String = when (value) {
        LiveLensMode.SETUP -> strings.setupHint
        LiveLensMode.WATCHING -> strings.watching
        LiveLensMode.PAUSED -> strings.pause
        LiveLensMode.READING -> strings.reading
        LiveLensMode.TRANSLATING -> strings.translating
        LiveLensMode.ERROR -> strings.failed
    }

    private companion object {
        const val RESIZE_GRIP = 26
    }
}

private class LensPanel(private val strings: LiveLensStrings) : JPanel() {
    private var mode = LiveLensMode.SETUP
    private var message = strings.setupHint
    private val cards = mutableListOf<LiveLensCard>()
    private var animationPhase = 0f
    private var nextScanAt = 0L
    private var scanIntervalMillis = DEFAULT_SCAN_INTERVAL_MS
    private var lastCardAddedAt = 0L
    private val animation = Timer(80) {
        animationPhase = (animationPhase + 0.04f) % 1f
        repaint()
    }

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
    }

    fun setMode(value: LiveLensMode, status: String) {
        mode = value
        message = status
        cursor = if (value == LiveLensMode.SETUP) {
            Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
        if (value.isRunning()) animation.start() else animation.stop()
        repaint()
    }

    fun clearCards() {
        cards.clear()
        nextScanAt = 0L
        lastCardAddedAt = 0L
        repaint()
    }

    fun snapshotCompleted(nextScanDelayMillis: Long) {
        scanIntervalMillis = nextScanDelayMillis.coerceAtLeast(1L)
        nextScanAt = System.currentTimeMillis() + scanIntervalMillis
        if (mode.isRunning() && !animation.isRunning) animation.start()
        repaint()
    }

    fun addCard(
        id: Long,
        source: LiveLensTextBlock,
        translatedText: String,
        provider: String
    ) {
        val now = System.currentTimeMillis()
        cards += LiveLensCard(
            id = id,
            speaker = source.speaker,
            sourceText = source.text,
            translatedText = translatedText,
            provider = provider,
            createdAt = now
        )
        while (cards.size > MAX_STORED_CARDS) cards.removeAt(0)
        lastCardAddedAt = now
        repaint()
    }

    fun stopAnimation() {
        animation.stop()
    }

    fun resumeTimers() {
        if (mode.isRunning() && !animation.isRunning) animation.start()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val border = when (mode) {
                LiveLensMode.ERROR -> Color(225, 93, 93, 210)
                LiveLensMode.PAUSED -> Color(160, 170, 172, 180)
                else -> Color(59, 194, 169, 210)
            }
            g.color = Color(35, 171, 153, if (mode == LiveLensMode.SETUP) 20 else 3)
            g.fillRoundRect(1, 1, width - 3, height - 3, 18, 18)
            g.stroke = BasicStroke(if (mode == LiveLensMode.SETUP) 2f else 1.25f)
            g.color = border
            g.drawRoundRect(1, 1, width - 3, height - 3, 18, 18)

            if (mode.isRunning() && nextScanAt > 0L) {
                val remaining = (nextScanAt - System.currentTimeMillis()).coerceAtLeast(0L)
                val progress = 1.0 - remaining.toDouble() / scanIntervalMillis
                g.color = Color(66, 218, 188, 52)
                g.fillRoundRect(2, 2, width - 4, 4, 8, 8)
                g.color = Color(73, 224, 194, 190)
                g.fillRoundRect(
                    2,
                    2,
                    ((width - 4) * progress.coerceIn(0.0, 1.0)).roundToInt(),
                    4,
                    8,
                    8
                )
            }

            if (mode == LiveLensMode.READING || mode == LiveLensMode.TRANSLATING) {
                val bandX = ((width + 180) * animationPhase - 180).toInt()
                g.paint = GradientPaint(
                    bandX.toFloat(), 0f, Color(74, 226, 199, 0),
                    (bandX + 180).toFloat(), 0f, Color(74, 226, 199, 65)
                )
                g.fillRoundRect(2, 2, width - 4, 5, 10, 10)
            }

            paintStatus(g)
            paintCards(g)
            if (mode == LiveLensMode.SETUP) paintResizeGrip(g)
        } finally {
            g.dispose()
        }
    }

    private fun paintStatus(g: Graphics2D) {
        g.font = font.deriveFont(Font.BOLD, 11f)
        val status = if (mode == LiveLensMode.SETUP) message else "●  $message"
        val metrics = g.fontMetrics
        val statusWidth = min(width - 24, metrics.stringWidth(status) + 24)
        g.color = Color(12, 24, 28, 220)
        g.fillRoundRect(12, 11, statusWidth, 26, 13, 13)
        g.color = if (mode == LiveLensMode.ERROR) Color(244, 125, 125) else Color(102, 230, 203)
        g.drawString(status, 23, 29)

        if (mode.isRunning()) {
            val now = System.currentTimeMillis()
            val rightStatus = if (now - lastCardAddedAt <= NEW_INDICATOR_MS) {
                strings.newMessage
            } else {
                val remaining = (nextScanAt - now).coerceAtLeast(0L) / 1_000.0
                "${strings.nextScan} ${String.format(Locale.ROOT, "%.1f", remaining)} ${strings.secondsShort}"
            }
            g.font = font.deriveFont(Font.PLAIN, 10.5f)
            val rightMetrics = g.fontMetrics
            val rightWidth = rightMetrics.stringWidth(rightStatus) + 20
            val rightX = width - rightWidth - 12
            if (rightX > 24 + statusWidth) {
                g.color = Color(12, 24, 28, 190)
                g.fillRoundRect(rightX, 11, rightWidth, 26, 13, 13)
                g.color = Color(157, 208, 199)
                g.drawString(rightStatus, rightX + 10, 29)
            }
        }
    }

    private fun paintCards(g: Graphics2D) {
        val cardWidth = (width - 24).coerceAtLeast(230)
        val prepared = cards.map { card ->
            g.font = font.deriveFont(Font.PLAIN, 14f)
            val nicknameColumnWidth = if (card.speaker.isNullOrBlank()) 0 else {
                (cardWidth / 4).coerceIn(86, 132)
            }
            val messageWidth = cardWidth - nicknameColumnWidth - 26
            val lines = wrap(card.translatedText, g.fontMetrics, messageWidth).take(4)
            val cardHeight = max(50, 18 + lines.size * 19)
            PreparedCard(card, lines, nicknameColumnWidth, cardHeight)
        }
        val placements = calculateLiveLensFeedPlacements(
            cardHeights = prepared.map(PreparedCard::height),
            panelHeight = height
        )

        placements.forEach { placement ->
            val current = prepared[placement.cardIndex]
            val card = current.card
            val y = placement.y
            val x = 12

            g.color = Color(9, 20, 24, 214)
            g.fill(RoundRectangle2D.Float(
                x.toFloat(), y.toFloat(), cardWidth.toFloat(), current.height.toFloat(), 12f, 12f
            ))
            g.stroke = BasicStroke(1f)
            g.color = Color(72, 214, 187, 105)
            g.drawRoundRect(x, y, cardWidth, current.height, 12, 12)
            g.color = Color(72, 214, 187, 220)
            g.fillRoundRect(x, y + 7, 3, current.height - 14, 3, 3)

            var messageX = x + 13
            card.speaker?.takeIf(String::isNotBlank)?.let { speaker ->
                g.font = font.deriveFont(Font.BOLD, 12f)
                g.color = Color(111, 224, 203)
                g.drawString(abbreviate(speaker, 18), x + 13, y + 29)
                val dividerX = x + current.nicknameColumnWidth
                g.color = Color(72, 214, 187, 80)
                g.drawLine(dividerX, y + 10, dividerX, y + current.height - 10)
                messageX = dividerX + 13
            }

            g.font = font.deriveFont(Font.PLAIN, 14f)
            g.color = Color.WHITE
            current.lines.forEachIndexed { index, line ->
                g.drawString(line, messageX, y + 29 + index * 19)
            }
        }
    }

    private fun wrap(text: String, metrics: FontMetrics, maxWidth: Int): List<String> {
        val result = mutableListOf<String>()
        text.lineSequence().forEach { paragraph ->
            var line = ""
            paragraph.split(Regex("\\s+")).filter(String::isNotBlank).forEach { word ->
                val candidate = if (line.isBlank()) word else "$line $word"
                if (metrics.stringWidth(candidate) <= maxWidth) line = candidate
                else {
                    if (line.isNotBlank()) result += line
                    line = word
                }
            }
            if (line.isNotBlank()) result += line
        }
        return result
    }

    private fun abbreviate(text: String, maxLength: Int): String =
        if (text.length <= maxLength) text else text.take(maxLength - 1).trimEnd() + "…"

    private fun paintResizeGrip(g: Graphics2D) {
        g.color = Color(86, 216, 191, 210)
        repeat(3) { index ->
            val inset = 8 + index * 5
            g.drawLine(width - inset, height - 5, width - 5, height - inset)
        }
    }

    private companion object {
        const val MAX_STORED_CARDS = 200
        const val DEFAULT_SCAN_INTERVAL_MS = 5_000L
        const val NEW_INDICATOR_MS = 6_000L
    }

    private data class PreparedCard(
        val card: LiveLensCard,
        val lines: List<String>,
        val nicknameColumnWidth: Int,
        val height: Int
    )
}

private fun LiveLensMode.isRunning(): Boolean =
    this == LiveLensMode.WATCHING ||
        this == LiveLensMode.READING ||
        this == LiveLensMode.TRANSLATING ||
        this == LiveLensMode.ERROR

internal fun mapNativePointToLocal(
    point: Point,
    nativeBounds: Rectangle,
    localSize: Dimension
): Point {
    if (nativeBounds.width <= 0 || nativeBounds.height <= 0) return Point(point)
    val scaleX = localSize.width.toDouble() / nativeBounds.width
    val scaleY = localSize.height.toDouble() / nativeBounds.height
    return Point(
        ((point.x - nativeBounds.x) * scaleX).roundToInt(),
        ((point.y - nativeBounds.y) * scaleY).roundToInt()
    )
}

internal data class LiveLensCardPlacement(
    val cardIndex: Int,
    val y: Int
)

internal fun calculateLiveLensFeedPlacements(
    cardHeights: List<Int>,
    panelHeight: Int,
    statusBottom: Int = LIVE_LENS_STATUS_BOTTOM,
    bottomPadding: Int = 12,
    gap: Int = 5
): List<LiveLensCardPlacement> {
    var cursorBottom = panelHeight - bottomPadding
    val result = mutableListOf<LiveLensCardPlacement>()
    for (index in cardHeights.indices.reversed()) {
        val cardHeight = cardHeights[index].coerceAtLeast(1)
        val y = cursorBottom - cardHeight
        if (y < statusBottom) break
        result += LiveLensCardPlacement(index, y)
        cursorBottom = y - gap
    }
    return result.asReversed()
}

private const val LIVE_LENS_STATUS_BOTTOM = 44
