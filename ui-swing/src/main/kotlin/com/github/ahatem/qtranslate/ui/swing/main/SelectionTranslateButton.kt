package com.github.ahatem.qtranslate.ui.swing.main

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import kotlin.math.abs
import kotlin.math.max
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

/**
 * A small floating button shown next to text the user selected in another application.
 *
 * Deliberately unobtrusive: it never takes focus, fades in and out rather than snapping,
 * and keeps itself out of the way of the selection that triggered it.
 *
 * ### Behaviour
 * - Auto-hides after [VISIBLE_MS], but the countdown pauses while the pointer is over it,
 *   so the button never disappears from under a user who is reaching for it.
 * - Repositions itself to stay on the monitor under the pointer, flipping to the other
 *   side of the cursor near a screen edge instead of being clamped half off-screen.
 * - [dismissIfOutside] lets the caller close it when the user clicks elsewhere.
 *
 * ### Rendering
 * All colours are read from [UIManager] at paint time, so the button follows theme
 * changes without needing to be rebuilt. When the platform supports per-pixel
 * translucency the window is transparent and the button paints its own rounded body
 * and soft shadow; otherwise it falls back to a plain opaque square.
 */
internal class SelectionTranslateButton internal constructor(
    owner: Window,
    private val translateIcon: FlatSVGIcon,
    private val replaceIcon: FlatSVGIcon,
    private val translateTooltip: String,
    private val replaceTooltip: String,
    private val onTranslate: (String) -> Unit,
    private val onTranslateAndReplace: (String, Long) -> Unit
) : JWindow(owner) {

    constructor(
        owner: Window,
        iconManager: IconManager,
        translateTooltip: String,
        replaceTooltip: String,
        onTranslate: (String) -> Unit,
        onTranslateAndReplace: (String, Long) -> Unit
    ) : this(
        owner = owner,
        translateIcon = (iconManager.getIcon(Icons.TRANSLATE, ICON_SIZE, ICON_SIZE) as FlatSVGIcon)
            .applyForegroundColorFilter(),
        replaceIcon = (iconManager.getIcon(Icons.SWAP, ICON_SIZE, ICON_SIZE) as FlatSVGIcon)
            .applyForegroundColorFilter(),
        translateTooltip = translateTooltip,
        replaceTooltip = replaceTooltip,
        onTranslate = onTranslate,
        onTranslateAndReplace = onTranslateAndReplace
    )

    private val payload = SelectionButtonPayload()
    private val hideTimer = Timer(VISIBLE_MS) { fadeOutAndHide() }.apply { isRepeats = false }
    private var fadeTimer: Timer? = null

    private val translucent: Boolean = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
    }.getOrDefault(false)

    private val face = ButtonFace()

    init {
        type = Window.Type.POPUP
        isAlwaysOnTop = true
        // Never take focus — the user is mid-task in another application and the
        // button appearing must not interrupt whatever they are doing.
        focusableWindowState = false
        if (translucent) background = TRANSPARENT

        contentPane = face
        size = Dimension(CONTENT_WIDTH + PADDING * 2, FACE_SIZE + PADDING * 2)
        // Регистрирует компонент в ToolTipManager; конкретный текст выбирается по кнопке.
        face.toolTipText = translateTooltip
    }

    /** Shows the button near [pointer] for [text], choosing a corner that stays on screen. */
    fun showAt(
        pointer: Point,
        text: String,
        capturedAtMillis: Long = System.currentTimeMillis()
    ) {
        if (text.isBlank()) return
        payload.remember(text, capturedAtMillis)
        face.hoveredAction = null
        location = placeNear(pointer)

        if (!isVisible) {
            setOpacitySafely(0f)
            isVisible = true
        }
        fadeTo(1f)
        hideTimer.restart()
    }

    /** Closes the button when the user presses the mouse anywhere outside it. */
    fun dismissIfOutside(screenPoint: Point) {
        if (!isVisible) return
        if (!bounds.contains(screenPoint)) dismiss()
    }

    fun dismiss() {
        hideTimer.stop()
        fadeTimer?.stop()
        fadeTimer = null
        isVisible = false
        payload.clear()
    }

    override fun dispose() {
        hideTimer.stop()
        fadeTimer?.stop()
        super.dispose()
    }

    // ── Placement ────────────────────────────────────────────────────────────

    /**
     * Prefers below-right of the cursor, which is where the drag ended and therefore
     * clear of the text just selected. Flips horizontally or vertically when that
     * corner would leave the monitor, so the button is never clipped or shoved on top
     * of the selection.
     */
    private fun placeNear(pointer: Point): Point {
        return calculateSelectionButtonLocation(
            pointer = pointer,
            windowSize = size,
            screenWorkArea = screenWorkAreaFor(pointer),
            faceSize = Dimension(CONTENT_WIDTH, FACE_SIZE),
            padding = PADDING,
            gap = GAP
        )
    }

    private fun screenWorkAreaFor(pointer: Point): Rectangle {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val configuration = environment.screenDevices
            .map { it.defaultConfiguration }
            .firstOrNull { it.bounds.contains(pointer) }
            ?: return environment.maximumWindowBounds
        val bounds = configuration.bounds
        val insets = runCatching {
            Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        }.getOrNull() ?: return bounds
        return Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            max(1, bounds.width - insets.left - insets.right),
            max(1, bounds.height - insets.top - insets.bottom)
        )
    }

    // ── Fading ───────────────────────────────────────────────────────────────

    private fun fadeTo(target: Float, onDone: (() -> Unit)? = null) {
        fadeTimer?.stop()
        if (!translucent) {
            setOpacitySafely(target)
            onDone?.invoke()
            return
        }
        val start = opacity
        if (abs(start - target) < 0.01f) {
            onDone?.invoke()
            return
        }
        var step = 0
        fadeTimer = Timer(FADE_MS / FADE_STEPS) { event ->
            step++
            setOpacitySafely(start + (target - start) * (step.toFloat() / FADE_STEPS))
            if (step >= FADE_STEPS) {
                (event.source as Timer).stop()
                onDone?.invoke()
            }
        }.apply { isRepeats = true; start() }
    }

    private fun fadeOutAndHide() = fadeTo(0f) { if (opacity <= 0.01f) dismiss() }

    private fun setOpacitySafely(value: Float) {
        runCatching { opacity = value.coerceIn(0f, 1f) }
    }

    // ── Painting ─────────────────────────────────────────────────────────────

    private inner class ButtonFace : JComponent() {
        var hoveredAction: ButtonAction? = null
            set(value) {
                if (field != value) { field = value; repaint() }
            }

        init {
            isOpaque = !translucent
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val mouseHandler = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hoveredAction = actionAt(e.point)
                    // Hold the button open while the pointer is on it — nothing is more
                    // annoying than a target that vanishes as you reach for it.
                    hideTimer.stop()
                    fadeTimer?.stop()
                    setOpacitySafely(1f)
                }

                override fun mouseExited(e: MouseEvent) {
                    hoveredAction = null
                    if (isVisible) hideTimer.restart()
                }

                override fun mousePressed(e: MouseEvent) {
                    hoveredAction = actionAt(e.point)
                    repaint()
                }

                override fun mouseMoved(e: MouseEvent) {
                    hoveredAction = actionAt(e.point)
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    val action = actionAt(e.point) ?: return
                    val request = payload.consume() ?: return
                    dismiss()
                    deliverSelectionButtonAction(
                        action = action,
                        request = request,
                        onTranslate = onTranslate,
                        onTranslateAndReplace = onTranslateAndReplace
                    )
                }
            }
            addMouseListener(mouseHandler)
            addMouseMotionListener(mouseHandler)
        }

        override fun getToolTipText(event: MouseEvent): String = when (actionAt(event.point)) {
            ButtonAction.TRANSLATE -> translateTooltip
            ButtonAction.TRANSLATE_AND_REPLACE -> replaceTooltip
            null -> ""
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                paintButton(g2, ButtonAction.TRANSLATE, translateRect(), translateIcon)
                paintButton(g2, ButtonAction.TRANSLATE_AND_REPLACE, replaceRect(), replaceIcon)
            } finally {
                g2.dispose()
            }
        }

        private fun paintButton(
            g2: Graphics2D,
            action: ButtonAction,
            faceRect: Rectangle,
            icon: FlatSVGIcon
        ) {
            if (translucent) paintShadow(g2, faceRect)
            val hovered = hoveredAction == action
            g2.color = if (hovered) hoverBackground() else background()
            g2.fillRoundRect(faceRect.x, faceRect.y, faceRect.width, faceRect.height, ARC, ARC)
            g2.color = borderColor(hovered)
            g2.drawRoundRect(faceRect.x, faceRect.y, faceRect.width - 1, faceRect.height - 1, ARC, ARC)
            icon.paintIcon(
                this,
                g2,
                faceRect.x + (faceRect.width - icon.iconWidth) / 2,
                faceRect.y + (faceRect.height - icon.iconHeight) / 2
            )
        }

        private fun translateRect() = Rectangle(PADDING, PADDING, FACE_SIZE, FACE_SIZE)

        private fun replaceRect() = Rectangle(
            PADDING + FACE_SIZE + BUTTON_GAP,
            PADDING,
            FACE_SIZE,
            FACE_SIZE
        )

        private fun actionAt(point: Point): ButtonAction? = when {
            translateRect().contains(point) -> ButtonAction.TRANSLATE
            replaceRect().contains(point) -> ButtonAction.TRANSLATE_AND_REPLACE
            else -> null
        }

        /** Layered translucent rounded rects — cheap approximation of a soft drop shadow. */
        private fun paintShadow(g2: Graphics2D, face: Rectangle) {
            val composite = g2.composite
            for (i in PADDING downTo 1) {
                val alpha = SHADOW_ALPHA * (1f - i.toFloat() / (PADDING + 1))
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                g2.color = Color.BLACK
                g2.fillRoundRect(
                    face.x - i, face.y - i + SHADOW_Y_OFFSET,
                    face.width + i * 2, face.height + i * 2,
                    ARC + i, ARC + i
                )
            }
            g2.composite = composite
        }

        /**
         * A surface that reads as raised above whatever is behind it. Theme background
         * colours alone are too close to the window the button floats over — especially
         * in dark themes — so the base colour is nudged away from the page.
         */
        private fun background(): Color {
            val base = UIManager.getColor("Panel.background") ?: Color.WHITE
            return if (FlatSVGIcon.isDarkLaf()) base.shift(0.15f) else base.shift(0.55f)
        }

        /**
         * Hover leans on the theme accent rather than `Button.hoverBackground`, which in
         * several light themes is almost identical to the base and leaves the button
         * looking inert under the cursor.
         */
        private fun hoverBackground(): Color = background().blend(accent(), 0.20f)

        private fun borderColor(hovered: Boolean): Color =
            if (hovered) accent()
            else UIManager.getColor("Component.borderColor")
                ?: UIManager.getColor("Separator.foreground")
                ?: Color.GRAY

        private fun accent(): Color =
            UIManager.getColor("Component.focusColor")
                ?: UIManager.getColor("Component.accentColor")
                ?: UIManager.getColor("Component.focusedBorderColor")
                ?: Color(0x2D_7D_F6)

        /** Moves a colour toward white (positive [amount]) by the given fraction. */
        private fun Color.shift(amount: Float): Color = blend(Color.WHITE, amount)

        private fun Color.blend(other: Color, ratio: Float): Color {
            val r = ratio.coerceIn(0f, 1f)
            return Color(
                (red + (other.red - red) * r).toInt().coerceIn(0, 255),
                (green + (other.green - green) * r).toInt().coerceIn(0, 255),
                (blue + (other.blue - blue) * r).toInt().coerceIn(0, 255)
            )
        }
    }

    private companion object {
        const val ICON_SIZE = 14

        /** Visible button body; the window is larger to leave room for the shadow. */
        const val FACE_SIZE = 26
        const val BUTTON_GAP = 3
        const val CONTENT_WIDTH = FACE_SIZE * 2 + BUTTON_GAP
        const val PADDING = 5
        const val ARC = 9

        /** Distance from the cursor to the nearest edge of the button body. */
        const val GAP = 8

        const val VISIBLE_MS = 4_000
        const val FADE_MS = 140
        const val FADE_STEPS = 7

        const val SHADOW_ALPHA = 0.16f
        const val SHADOW_Y_OFFSET = 1

        val TRANSPARENT = Color(0, 0, 0, 0)
    }

}

internal enum class ButtonAction {
    TRANSLATE,
    TRANSLATE_AND_REPLACE
}

/** Одноразовое состояние кнопки: старое выделение нельзя повторно отправить после dismiss. */
internal class SelectionButtonPayload {
    data class Value(val text: String, val capturedAtMillis: Long)

    private var value: Value? = null

    fun remember(text: String, capturedAtMillis: Long) {
        value = Value(text, capturedAtMillis)
    }

    fun consume(): Value? = value.also { value = null }

    fun clear() {
        value = null
    }
}

internal fun deliverSelectionButtonAction(
    action: ButtonAction,
    request: SelectionButtonPayload.Value,
    onTranslate: (String) -> Unit,
    onTranslateAndReplace: (String, Long) -> Unit
) {
    if (request.text.isBlank()) return
    when (action) {
        ButtonAction.TRANSLATE -> onTranslate(request.text)
        ButtonAction.TRANSLATE_AND_REPLACE ->
            onTranslateAndReplace(request.text, request.capturedAtMillis)
    }
}

/**
 * Геометрия в AWT user space. Кнопка предпочитает правый нижний угол, переворачивается
 * у края и целиком остаётся в рабочей области, включая мониторы с отрицательным origin.
 */
internal fun calculateSelectionButtonLocation(
    pointer: Point,
    windowSize: Dimension,
    screenWorkArea: Rectangle,
    faceSize: Dimension,
    padding: Int,
    gap: Int
): Point {
    val width = max(1, windowSize.width)
    val height = max(1, windowSize.height)
    val maxX = max(screenWorkArea.x, screenWorkArea.x + screenWorkArea.width - width)
    val maxY = max(screenWorkArea.y, screenWorkArea.y + screenWorkArea.height - height)

    var x = pointer.x + gap - padding
    var y = pointer.y + gap - padding
    if (x + width > screenWorkArea.x + screenWorkArea.width) {
        x = pointer.x - gap - faceSize.width - padding
    }
    if (y + height > screenWorkArea.y + screenWorkArea.height) {
        y = pointer.y - gap - faceSize.height - padding
    }

    return Point(
        x.coerceIn(screenWorkArea.x, maxX),
        y.coerceIn(screenWorkArea.y, maxY)
    )
}
