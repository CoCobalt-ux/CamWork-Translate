package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.Image
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.roundToInt

/** Решение для одного события mouseClicked на tray icon. */
internal enum class TrayClickDecision {
    SCHEDULE_TOGGLE,
    CANCEL_TOGGLE_AND_OPEN,
    IGNORE
}

/**
 * Отделяет семантику tray-кликов от Swing-таймера и действий UI.
 *
 * Первый левый клик нельзя исполнять сразу: он может оказаться первой
 * половиной двойного клика. Второй клик отменяет ожидающее переключение и
 * сохраняет прежнее действие — открытие окна.
 */
internal object TrayInteractionPolicy {
    fun decide(button: Int, clickCount: Int, isPopupTrigger: Boolean): TrayClickDecision {
        if (isPopupTrigger || button != MouseEvent.BUTTON1) return TrayClickDecision.IGNORE

        return when (clickCount) {
            1 -> TrayClickDecision.SCHEDULE_TOGGLE
            2 -> TrayClickDecision.CANCEL_TOGGLE_AND_OPEN
            else -> TrayClickDecision.IGNORE
        }
    }

    fun doubleClickDelayMs(desktopProperty: Any?, fallbackMs: Int = 500): Int {
        val configured = (desktopProperty as? Number)?.toInt()
        return configured?.takeIf { it > 0 } ?: fallbackMs
    }

    fun toggledAutoSelectionState(currentState: Boolean): Boolean = !currentState

    fun isAutoSelectionEffective(globalHotkeysEnabled: Boolean, autoSelectionEnabled: Boolean): Boolean =
        globalHotkeysEnabled && autoSelectionEnabled
}

internal data class TrayIconImages(
    val neutral: Image,
    val active: Image
)

/** Создаёт DPI-aware варианты tray icon из того же набора, что и иконки окна. */
internal object TrayIconImageFactory {
    private val activeColor = java.awt.Color(38, 208, 118)

    fun create(sourceImages: List<Image>): TrayIconImages {
        require(sourceImages.isNotEmpty()) { "Tray icon requires at least one source image" }

        val neutralVariants = sourceImages.map(::createNeutralVariant)
        val activeVariants = sourceImages.map(::createActiveVariant)
        return TrayIconImages(
            neutral = BaseMultiResolutionImage(*neutralVariants.toTypedArray()),
            active = BaseMultiResolutionImage(*activeVariants.toTypedArray())
        )
    }

    private fun createNeutralVariant(source: Image): BufferedImage {
        val result = copyToBufferedImage(source)
        for (y in 0 until result.height) {
            for (x in 0 until result.width) {
                val argb = result.getRGB(x, y)
                val alpha = argb ushr 24 and 0xff
                val red = argb ushr 16 and 0xff
                val green = argb ushr 8 and 0xff
                val blue = argb and 0xff
                val gray = (red * 0.2126 + green * 0.7152 + blue * 0.0722).roundToInt()
                result.setRGB(x, y, alpha shl 24 or (gray shl 16) or (gray shl 8) or gray)
            }
        }
        return result
    }

    private fun createActiveVariant(source: Image): BufferedImage {
        val result = copyToBufferedImage(source)
        val badgeSize = max(5, (minOf(result.width, result.height) * 0.32).roundToInt())
        val border = max(1, (badgeSize * 0.14).roundToInt())
        val x = result.width - badgeSize - 1
        val y = result.height - badgeSize - 1

        val graphics = result.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = java.awt.Color(255, 255, 255, 235)
            graphics.fillOval(x, y, badgeSize, badgeSize)
            graphics.color = activeColor
            graphics.fillOval(
                x + border,
                y + border,
                badgeSize - border * 2,
                badgeSize - border * 2
            )
        } finally {
            graphics.dispose()
        }
        return result
    }

    private fun copyToBufferedImage(source: Image): BufferedImage {
        val width = source.getWidth(null)
        val height = source.getHeight(null)
        require(width > 0 && height > 0) { "Tray icon source image is not loaded" }

        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { result ->
            val graphics = result.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.drawImage(source, 0, 0, width, height, null)
            } finally {
                graphics.dispose()
            }
        }
    }
}
