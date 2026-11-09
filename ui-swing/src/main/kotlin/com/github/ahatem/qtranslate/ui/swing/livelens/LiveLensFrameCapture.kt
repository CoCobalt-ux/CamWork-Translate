package com.github.ahatem.qtranslate.ui.swing.livelens

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.ui.swing.shared.util.toImageData
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot

/** Захватывает пиксели под LIVE-рамкой для игр и других графических поверхностей. */
internal fun interface LiveLensFrameCapture {
    fun capture(bounds: Rectangle): ImageData?
}

internal fun defaultLiveLensFrameCapture(): LiveLensFrameCapture? =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        RobotLiveLensFrameCapture()
    } else {
        null
    }

internal class RobotLiveLensFrameCapture : LiveLensFrameCapture {
    override fun capture(bounds: Rectangle): ImageData? {
        if (bounds.width < MIN_CAPTURE_SIDE || bounds.height < MIN_CAPTURE_SIDE) return null
        val center = Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
        val device = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .firstOrNull { candidate -> candidate.defaultConfiguration.bounds.contains(center) }
            ?: return null
        val screenBounds = device.defaultConfiguration.bounds
        if (!screenBounds.contains(bounds)) return null

        return runCatching {
            Robot(device).createScreenCapture(Rectangle(bounds)).toImageData("png")
        }.getOrNull()
    }

    private companion object {
        const val MIN_CAPTURE_SIDE = 20
    }
}

/**
 * Системный OCR возвращает строки без координат. Область выбирается вокруг одного игрового
 * субтитра, поэтому переносы объединяются обратно в одну реплику: при смене двухстрочного
 * субтитра детектор не потеряет его первую половину.
 */
internal fun liveLensOcrTextBlocks(text: String, bounds: Rectangle): List<LiveLensTextBlock> {
    val normalized = text.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (normalized.length < 2) return emptyList()

    val speakerMatch = SPEAKER_PREFIX.matchEntire(normalized)
    val speaker = speakerMatch?.groupValues?.get(1)?.trim()
    val message = speakerMatch?.groupValues?.get(2)?.trim().orEmpty().ifBlank { normalized }
    return listOf(
        LiveLensTextBlock(
            text = message,
            anchor = Point(bounds.x + 8, bounds.y + bounds.height / 2),
            speaker = speaker
        )
    )
}

private val SPEAKER_PREFIX = Regex("^([^:：]{2,32})[:：]\\s+(.{2,})$")
