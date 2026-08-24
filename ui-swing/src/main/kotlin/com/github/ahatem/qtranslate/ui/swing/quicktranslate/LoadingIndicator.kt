package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.main.layout.ResponsiveUi
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.MouseInfo
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.Path2D
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.OverlayLayout
import javax.swing.Timer
import javax.swing.UIManager

enum class LoadingIndicatorPhase {
    TRANSLATING,
    SUCCESS,
    ERROR
}

data class LoadingIndicatorState(
    val isVisible: Boolean,
    val phase: LoadingIndicatorPhase = LoadingIndicatorPhase.TRANSLATING,
    val message: String = "",
    /** Сообщение, которое заменит бесконечную загрузку по защитному таймауту. */
    val timeoutMessage: String = ""
) : UiState

internal object LoadingIndicatorTiming {
    const val MINIMUM_VISIBLE_MS = 300
    /** Чуть больше общего 30-секундного network timeout: длинный текст не получает ложную ошибку. */
    const val TRANSLATING_TIMEOUT_MS = 32_000
    const val SUCCESS_VISIBLE_MS = 1_000
    const val ERROR_VISIBLE_MS = 2_200

    fun autoHideDelay(phase: LoadingIndicatorPhase): Int? = when (phase) {
        LoadingIndicatorPhase.TRANSLATING -> null
        LoadingIndicatorPhase.SUCCESS -> SUCCESS_VISIBLE_MS
        LoadingIndicatorPhase.ERROR -> ERROR_VISIBLE_MS
    }
}

internal enum class LoadingIndicatorMark {
    NONE,
    CHECK,
    ERROR
}

internal object LoadingIndicatorVisuals {
    const val MARKER_SIZE = 16
    const val MAX_MESSAGE_WIDTH = 240

    fun markFor(phase: LoadingIndicatorPhase): LoadingIndicatorMark = when (phase) {
        LoadingIndicatorPhase.TRANSLATING -> LoadingIndicatorMark.NONE
        LoadingIndicatorPhase.SUCCESS -> LoadingIndicatorMark.CHECK
        LoadingIndicatorPhase.ERROR -> LoadingIndicatorMark.ERROR
    }

    fun displayMessage(message: String, maxWidth: Int, measure: (String) -> Int): String =
        ResponsiveUi.elideText(message, maxWidth, measure)
}

internal data class LoadingIndicatorMetrics(
    val markerSize: Int,
    val gap: Int,
    val horizontalInset: Int,
    val verticalInset: Int
)

/** Геометрия toast в одном месте, чтобы layout и DPI-тесты использовали одинаковые числа. */
internal object LoadingIndicatorGeometry {
    private const val GAP = 8
    private const val HORIZONTAL_INSET = 10
    private const val VERTICAL_INSET = 6

    fun metrics(scale: (Int) -> Int = UIScale::scale): LoadingIndicatorMetrics =
        LoadingIndicatorMetrics(
            markerSize = scale(LoadingIndicatorVisuals.MARKER_SIZE),
            gap = scale(GAP),
            horizontalInset = scale(HORIZONTAL_INSET),
            verticalInset = scale(VERTICAL_INSET)
        )

    fun preferredSize(
        messageSize: Dimension,
        metrics: LoadingIndicatorMetrics,
        borderWidth: Int = 0
    ): Dimension = Dimension(
        borderWidth * 2 + metrics.horizontalInset * 2 + metrics.markerSize +
            metrics.gap + messageSize.width,
        borderWidth * 2 + metrics.verticalInset * 2 +
            maxOf(metrics.markerSize, messageSize.height)
    )
}

/**
 * Marker и pulse живут в слоте по реальному preferred size, а не в колонке фиксированной ширины.
 * Поэтому масштабированный marker не может выйти поверх текста на 125–200% DPI.
 */
internal fun createLoadingIndicatorSurface(
    pulse: JComponent,
    marker: JComponent,
    messageLabel: JLabel,
    metrics: LoadingIndicatorMetrics = LoadingIndicatorGeometry.metrics()
): JPanel {
    val iconSize = Dimension(metrics.markerSize, metrics.markerSize)
    val iconSlot = JPanel().apply {
        isOpaque = false
        layout = OverlayLayout(this)
        preferredSize = iconSize
        minimumSize = iconSize
        maximumSize = iconSize

        pulse.alignmentX = 0.5f
        pulse.alignmentY = 0.5f
        marker.alignmentX = 0.5f
        marker.alignmentY = 0.5f
        add(pulse)
        add(marker)
    }

    return JPanel(GridBagLayout()).apply {
        isOpaque = true
        add(iconSlot, GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.CENTER
            insets = Insets(
                metrics.verticalInset,
                metrics.horizontalInset,
                metrics.verticalInset,
                0
            )
        })
        add(messageLabel, GridBagConstraints().apply {
            gridx = 1
            gridy = 0
            anchor = GridBagConstraints.CENTER
            insets = Insets(
                metrics.verticalInset,
                metrics.gap,
                metrics.verticalInset,
                metrics.horizontalInset
            )
        })
    }
}

/**
 * Пассивный индикатор возле курсора для фонового перевода.
 *
 * Окно никогда не получает фокус. Во время запроса три точки мягко пульсируют; затем
 * тот же overlay ненадолго показывает успех либо понятную ошибку. Отдельный таймаут
 * не позволяет индикатору зависнуть, если сетевой слой не прислал завершающее событие.
 */
@Suppress("UNUSED_PARAMETER")
class LoadingIndicator(owner: Frame) : JWindow(), Renderable<LoadingIndicatorState> {

    private val pulse = PulseDots()
    private val marker = StatusMarker()
    private val messageLabel = JLabel()
    private val surface = createLoadingIndicatorSurface(pulse, marker, messageLabel)

    private val positionUpdater = Timer(40) { positionNearPointer() }
    private var pendingHide: Timer? = null
    private var terminalHide: Timer? = null
    private var translatingTimeout: Timer? = null
    private var shownAt = 0L

    init {
        isAlwaysOnTop = true
        focusableWindowState = false
        setAutoRequestFocus(false)
        type = Type.UTILITY
        contentPane.add(surface)
        updateTheme()
        pack()
    }

    fun showTranslating(message: String, timeoutMessage: String) {
        render(
            LoadingIndicatorState(
                isVisible = true,
                phase = LoadingIndicatorPhase.TRANSLATING,
                message = message,
                timeoutMessage = timeoutMessage
            )
        )
    }

    fun showSuccess(message: String) {
        render(
            LoadingIndicatorState(
                isVisible = true,
                phase = LoadingIndicatorPhase.SUCCESS,
                message = message
            )
        )
    }

    fun showError(message: String) {
        render(
            LoadingIndicatorState(
                isVisible = true,
                phase = LoadingIndicatorPhase.ERROR,
                message = message
            )
        )
    }

    fun dismiss() {
        render(LoadingIndicatorState(isVisible = false))
    }

    override fun render(state: LoadingIndicatorState) {
        if (!state.isVisible) {
            requestHide()
            return
        }

        pendingHide?.stop()
        pendingHide = null
        terminalHide?.stop()
        terminalHide = null

        updateTheme()
        updateContent(state)
        pack()

        if (!isVisible) {
            positionNearPointer()
            shownAt = System.currentTimeMillis()
            isVisible = true
        }

        when (state.phase) {
            LoadingIndicatorPhase.TRANSLATING -> startTranslating(state.timeoutMessage)
            LoadingIndicatorPhase.SUCCESS,
            LoadingIndicatorPhase.ERROR -> showTerminal(state.phase)
        }
    }

    private fun updateContent(state: LoadingIndicatorState) {
        val accent = when (state.phase) {
            LoadingIndicatorPhase.TRANSLATING -> labelColor()
            LoadingIndicatorPhase.SUCCESS -> themeColor("Actions.Green", Color(0x3A, 0x9B, 0x58))
            LoadingIndicatorPhase.ERROR -> themeColor("Actions.Red", Color(0xD8, 0x4A, 0x4A))
        }
        val compactMessage = LoadingIndicatorVisuals.displayMessage(
            message = state.message,
            maxWidth = UIScale.scale(LoadingIndicatorVisuals.MAX_MESSAGE_WIDTH),
            measure = messageLabel.getFontMetrics(messageLabel.font)::stringWidth
        )
        messageLabel.text = compactMessage
        messageLabel.toolTipText = state.message.takeIf { it != compactMessage }
        messageLabel.foreground = labelColor()

        val translating = state.phase == LoadingIndicatorPhase.TRANSLATING
        pulse.isVisible = translating
        marker.isVisible = !translating
        marker.update(LoadingIndicatorVisuals.markFor(state.phase), accent)

        val background = UIManager.getColor("Panel.background") ?: Color.WHITE
        val baseBorder = UIManager.getColor("Component.borderColor") ?: background.darker()
        surface.background = background
        surface.border = BorderFactory.createLineBorder(blend(baseBorder, background, 0.35f), 1, true)
    }

    private fun startTranslating(timeoutMessage: String) {
        pulse.start()
        positionUpdater.start()
        translatingTimeout?.stop()
        translatingTimeout = Timer(LoadingIndicatorTiming.TRANSLATING_TIMEOUT_MS) {
            (it.source as Timer).stop()
            translatingTimeout = null
            showError(timeoutMessage.ifBlank { messageLabel.text })
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun showTerminal(phase: LoadingIndicatorPhase) {
        pulse.stop()
        positionUpdater.stop()
        translatingTimeout?.stop()
        translatingTimeout = null
        positionNearPointer()

        val delay = checkNotNull(LoadingIndicatorTiming.autoHideDelay(phase))
        terminalHide = Timer(delay) {
            (it.source as Timer).stop()
            terminalHide = null
            hideNow()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun requestHide() {
        if (!isVisible) return
        terminalHide?.stop()
        terminalHide = null

        val shownFor = System.currentTimeMillis() - shownAt
        if (shownFor >= LoadingIndicatorTiming.MINIMUM_VISIBLE_MS) {
            hideNow()
            return
        }
        if (pendingHide != null) return
        pendingHide = Timer((LoadingIndicatorTiming.MINIMUM_VISIBLE_MS - shownFor).toInt()) {
            (it.source as Timer).stop()
            pendingHide = null
            hideNow()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun hideNow() {
        pendingHide?.stop()
        terminalHide?.stop()
        translatingTimeout?.stop()
        pendingHide = null
        terminalHide = null
        translatingTimeout = null
        pulse.stop()
        positionUpdater.stop()
        isVisible = false
    }

    private fun positionNearPointer() {
        val pointer = MouseInfo.getPointerInfo() ?: return
        val location = pointer.location
        val configuration = pointer.device.defaultConfiguration
        val bounds = configuration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)

        val left = bounds.x + insets.left
        val top = bounds.y + insets.top
        val right = bounds.x + bounds.width - insets.right
        val bottom = bounds.y + bounds.height - insets.bottom
        val gap = UIScale.scale(14)

        val preferredX = location.x + gap
        val preferredY = location.y + UIScale.scale(20)
        val x = if (preferredX + width <= right) preferredX else location.x - width - gap
        val y = if (preferredY + height <= bottom) preferredY else location.y - height - gap
        setLocation(x.coerceAtLeast(left), y.coerceAtLeast(top))
    }

    private fun updateTheme() {
        val background = UIManager.getColor("Panel.background") ?: Color.WHITE
        val borderColor = UIManager.getColor("Component.borderColor") ?: background.darker()
        surface.background = background
        surface.border = BorderFactory.createLineBorder(blend(borderColor, background, 0.35f), 1, true)
    }

    private fun labelColor(): Color = UIManager.getColor("Label.foreground") ?: Color.DARK_GRAY

    private fun themeColor(key: String, fallback: Color): Color = UIManager.getColor(key) ?: fallback

    private fun blend(base: Color, accent: Color, amount: Float): Color {
        val keep = 1f - amount.coerceIn(0f, 1f)
        return Color(
            (base.red * keep + accent.red * amount).toInt().coerceIn(0, 255),
            (base.green * keep + accent.green * amount).toInt().coerceIn(0, 255),
            (base.blue * keep + accent.blue * amount).toInt().coerceIn(0, 255)
        )
    }

    /** Компактный векторный маркер остаётся чётким и различимым без опоры только на цвет. */
    private class StatusMarker : JComponent() {
        private var mark = LoadingIndicatorMark.NONE
        private var accent = Color(0x3A, 0x9B, 0x58)

        init {
            preferredSize = Dimension(
                UIScale.scale(LoadingIndicatorVisuals.MARKER_SIZE),
                UIScale.scale(LoadingIndicatorVisuals.MARKER_SIZE)
            )
            minimumSize = preferredSize
            isOpaque = false
            isVisible = false
        }

        fun update(mark: LoadingIndicatorMark, accent: Color) {
            this.mark = mark
            this.accent = accent
            repaint()
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            if (mark == LoadingIndicatorMark.NONE) return

            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val inset = UIScale.scale(1)
                val diameter = (minOf(width, height) - inset * 2).coerceAtLeast(1)
                val x = (width - diameter) / 2
                val y = (height - diameter) / 2
                g.stroke = BasicStroke(
                    UIScale.scale(1.5f),
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
                )
                when (mark) {
                    LoadingIndicatorMark.CHECK -> {
                        g.color = accent
                        g.fillOval(x, y, diameter, diameter)
                        g.color = Color.WHITE
                        val check = Path2D.Float().apply {
                            moveTo(x + diameter * 0.25f, y + diameter * 0.50f)
                            lineTo(x + diameter * 0.43f, y + diameter * 0.66f)
                            lineTo(x + diameter * 0.75f, y + diameter * 0.32f)
                        }
                        g.draw(check)
                    }
                    LoadingIndicatorMark.ERROR -> {
                        g.color = accent
                        g.drawOval(x + 1, y + 1, (diameter - 2).coerceAtLeast(1), (diameter - 2).coerceAtLeast(1))
                        g.drawLine(
                            x + diameter / 2,
                            y + (diameter * 0.27f).toInt(), x + diameter / 2,
                            y + (diameter * 0.56f).toInt()
                        )
                        val dot = UIScale.scale(1.8f).toInt().coerceAtLeast(2)
                        g.fillOval(x + (diameter - dot) / 2, y + (diameter * 0.68f).toInt(), dot, dot)
                    }
                    LoadingIndicatorMark.NONE -> Unit
                }
            } finally {
                g.dispose()
            }
        }
    }

    private class PulseDots : JComponent() {
        private var activeDot = 0
        private val timer = Timer(180) {
            activeDot = (activeDot + 1) % DOT_COUNT
            repaint()
        }

        init {
            preferredSize = Dimension(UIScale.scale(16), UIScale.scale(8))
            minimumSize = preferredSize
            isOpaque = false
        }

        fun start() {
            if (!timer.isRunning) timer.start()
        }

        fun stop() {
            timer.stop()
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val diameter = UIScale.scale(3)
                val gap = UIScale.scale(2)
                val totalWidth = DOT_COUNT * diameter + (DOT_COUNT - 1) * gap
                val startX = (width - totalWidth) / 2
                val y = (height - diameter) / 2
                val base = UIManager.getColor("Label.foreground") ?: Color.DARK_GRAY

                repeat(DOT_COUNT) { index ->
                    val alpha = if (index == activeDot) 230 else 75
                    g.color = Color(base.red, base.green, base.blue, alpha)
                    g.fillOval(startX + index * (diameter + gap), y, diameter, diameter)
                }
            } finally {
                g.dispose()
            }
        }

        private companion object {
            const val DOT_COUNT = 3
        }
    }
}
