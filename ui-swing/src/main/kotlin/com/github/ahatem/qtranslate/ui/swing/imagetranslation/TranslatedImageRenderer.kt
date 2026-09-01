package com.github.ahatem.qtranslate.ui.swing.imagetranslation

import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslatedImageRegion
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min

/** Создаёт новый кадр: закрывает исходные буквы и вписывает перевод в те же области. */
internal object TranslatedImageRenderer {
    fun render(source: BufferedImage, regions: List<TranslatedImageRegion>): BufferedImage {
        val result = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        result.createGraphics().use { graphics ->
            graphics.drawImage(source, 0, 0, null)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            regions.forEach { region -> paintRegion(graphics, source, region) }
        }
        return result
    }

    private fun paintRegion(
        graphics: Graphics2D,
        source: BufferedImage,
        region: TranslatedImageRegion
    ) {
        val raw = region.bounds.let { Rectangle(it.x, it.y, it.width, it.height) }
        val clipped = raw.intersection(Rectangle(0, 0, source.width, source.height))
        if (clipped.isEmpty || region.translatedText.isBlank()) return

        val background = analyzeBackground(source, clipped)
        val horizontalPadding = max(8, clipped.height / 3)
        val verticalPadding = max(5, clipped.height / 5)
        val paintBounds = Rectangle(
            clipped.x - horizontalPadding,
            clipped.y - verticalPadding,
            clipped.width + horizontalPadding * 2,
            clipped.height + verticalPadding * 2
        ).intersection(Rectangle(0, 0, source.width, source.height))

        val fill = if (background.isUniform) {
            background.color
        } else {
            Color(12, 22, 26, 224)
        }
        val arc = min(paintBounds.height, 22)
        graphics.color = fill
        graphics.fillRoundRect(
            paintBounds.x,
            paintBounds.y,
            paintBounds.width,
            paintBounds.height,
            arc,
            arc
        )
        if (!background.isUniform) {
            graphics.color = Color(73, 210, 184, 170)
            graphics.stroke = BasicStroke(1f)
            graphics.drawRoundRect(
                paintBounds.x,
                paintBounds.y,
                paintBounds.width - 1,
                paintBounds.height - 1,
                arc,
                arc
            )
        }

        val textColor = if (luminance(fill) >= 145) Color(20, 24, 27) else Color(246, 250, 249)
        paintFittedText(graphics, region.translatedText.trim(), paintBounds, textColor)
    }

    private fun paintFittedText(
        graphics: Graphics2D,
        text: String,
        bounds: Rectangle,
        color: Color
    ) {
        val inner = Rectangle(
            bounds.x + TEXT_INSET,
            bounds.y + TEXT_INSET,
            (bounds.width - TEXT_INSET * 2).coerceAtLeast(1),
            (bounds.height - TEXT_INSET * 2).coerceAtLeast(1)
        )
        val maximum = min(MAX_FONT_SIZE, max(MIN_FONT_SIZE, (bounds.height * 0.72).toInt()))
        var chosenFont = Font(Font.SANS_SERIF, Font.BOLD, MIN_FONT_SIZE)
        var chosenLines = listOf(text)
        for (size in maximum downTo MIN_FONT_SIZE) {
            val font = Font(Font.SANS_SERIF, Font.BOLD, size)
            val metrics = graphics.getFontMetrics(font)
            val lines = wrap(text, inner.width, metrics::stringWidth)
            val totalHeight = lines.size * metrics.height
            if (lines.isNotEmpty() && totalHeight <= inner.height) {
                chosenFont = font
                chosenLines = lines
                break
            }
        }

        graphics.font = chosenFont
        graphics.color = color
        val metrics = graphics.fontMetrics
        val totalHeight = chosenLines.size * metrics.height
        var baseline = inner.y + (inner.height - totalHeight) / 2 + metrics.ascent
        chosenLines.forEach { line ->
            val x = inner.x + (inner.width - metrics.stringWidth(line)) / 2
            graphics.drawString(line, x, baseline)
            baseline += metrics.height
        }
    }

    private fun wrap(text: String, maxWidth: Int, widthOf: (String) -> Int): List<String> {
        val words = text.split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && widthOf(candidate) > maxWidth) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun analyzeBackground(image: BufferedImage, bounds: Rectangle): BackgroundSample {
        val colors = mutableListOf<Color>()
        val stepX = max(1, bounds.width / SAMPLE_GRID)
        val stepY = max(1, bounds.height / SAMPLE_GRID)
        var y = bounds.y
        while (y < bounds.y + bounds.height) {
            var x = bounds.x
            while (x < bounds.x + bounds.width) {
                colors += Color(image.getRGB(x, y), true)
                x += stepX
            }
            y += stepY
        }
        if (colors.isEmpty()) return BackgroundSample(Color.WHITE, false)
        val median = Color(
            colors.map(Color::getRed).sorted()[colors.size / 2],
            colors.map(Color::getGreen).sorted()[colors.size / 2],
            colors.map(Color::getBlue).sorted()[colors.size / 2]
        )
        val matching = colors.count { color ->
            maxOf(
                kotlin.math.abs(color.red - median.red),
                kotlin.math.abs(color.green - median.green),
                kotlin.math.abs(color.blue - median.blue)
            ) <= UNIFORM_COLOR_TOLERANCE
        }
        return BackgroundSample(
            color = median,
            isUniform = matching.toDouble() / colors.size >= MIN_UNIFORM_COLOR_RATIO
        )
    }

    private fun luminance(color: Color): Double =
        color.red * 0.2126 + color.green * 0.7152 + color.blue * 0.0722

    private data class BackgroundSample(val color: Color, val isUniform: Boolean)

    private const val MIN_FONT_SIZE = 9
    private const val MAX_FONT_SIZE = 48
    private const val TEXT_INSET = 5
    private const val SAMPLE_GRID = 12
    private const val UNIFORM_COLOR_TOLERANCE = 42
    private const val MIN_UNIFORM_COLOR_RATIO = 0.56
}

private inline fun <T : Graphics2D> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}
