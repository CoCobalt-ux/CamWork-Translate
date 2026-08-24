package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.formdev.flatlaf.util.UIScale
import java.awt.Dimension

/** Предсказуемые границы popup, отделённые от Swing-измерения для точечных тестов. */
internal object QuickTranslateSizing {
    private const val PASSIVE_MIN_WIDTH = 320
    private const val REGULAR_MIN_WIDTH = 380
    // 100 px оставляют однострочному AdvancedTextPane полный viewport: при 92 px FlatLaf
    // показывал почти полноразмерный scrollbar из-за переполнения на несколько пикселей.
    private const val PASSIVE_MIN_HEIGHT = 100
    private const val REGULAR_MIN_HEIGHT = 104
    private const val PASSIVE_LINE_CHARACTERS = 56
    private const val REGULAR_LINE_CHARACTERS = 64
    private const val MAX_WIDTH = 560
    private const val MAX_SCREEN_WIDTH_FRACTION = 0.38
    private const val MAX_SCREEN_HEIGHT_FRACTION = 0.38
    private const val HORIZONTAL_CHROME = 32
    private const val HEADER_CHROME = 16

    fun minimumSize(passive: Boolean): Dimension = Dimension(
        UIScale.scale(if (passive) PASSIVE_MIN_WIDTH else REGULAR_MIN_WIDTH),
        UIScale.scale(if (passive) PASSIVE_MIN_HEIGHT else REGULAR_MIN_HEIGHT)
    )

    fun shouldAutoSize(configuredAutoSize: Boolean, passive: Boolean): Boolean =
        configuredAutoSize || passive

    fun maxDialogWidth(averageCharacterWidth: Int, screenWidth: Int, passive: Boolean): Int {
        val characters = if (passive) PASSIVE_LINE_CHARACTERS else REGULAR_LINE_CHARACTERS
        val comfortable = averageCharacterWidth.coerceAtLeast(1) * characters + horizontalChrome()
        val screenCap = (screenWidth * MAX_SCREEN_WIDTH_FRACTION).toInt()
        return minOf(comfortable, screenCap, UIScale.scale(MAX_WIDTH))
            .coerceAtLeast(minimumSize(passive).width)
    }

    fun maxDialogHeight(screenHeight: Int, passive: Boolean): Int =
        (screenHeight * MAX_SCREEN_HEIGHT_FRACTION).toInt()
            .coerceAtLeast(minimumSize(passive).height)

    fun targetWidth(
        naturalTextWidth: Int,
        headerWidth: Int,
        maximumWidth: Int,
        passive: Boolean
    ): Int {
        val minimum = minimumSize(passive).width
        val wanted = maxOf(
            minimum,
            naturalTextWidth + horizontalChrome(),
            headerWidth + UIScale.scale(HEADER_CHROME)
        )
        return wanted.coerceAtMost(maximumWidth.coerceAtLeast(minimum))
    }

    fun targetHeight(
        measuredTextHeight: Int,
        chromeHeight: Int,
        maximumHeight: Int,
        passive: Boolean
    ): Int {
        val minimum = minimumSize(passive).height
        return (measuredTextHeight + chromeHeight)
            .coerceAtLeast(minimum)
            .coerceAtMost(maximumHeight.coerceAtLeast(minimum))
    }

    fun horizontalChrome(): Int = UIScale.scale(HORIZONTAL_CHROME)
}
