package com.github.ahatem.qtranslate.ui.swing.imagetranslation

import com.github.ahatem.qtranslate.api.ocr.OCRBounds
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslatedImageRegion
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TranslatedImageRendererTest {
    @Test
    fun `создает диагностический игровой кадр при переданных путях`() {
        val sourcePath = System.getenv("CAMWORK_IMAGE_RENDER_SOURCE")
            ?.takeIf(String::isNotBlank)
            ?: return
        val outputPath = System.getenv("CAMWORK_IMAGE_RENDER_OUTPUT")
            ?.takeIf(String::isNotBlank)
            ?: return
        val source = requireNotNull(ImageIO.read(File(sourcePath)))
        val result = TranslatedImageRenderer.render(
            source,
            listOf(
                TranslatedImageRegion("Hah...", "Хах...", OCRBounds(804, 98, 79, 20)),
                TranslatedImageRegion(
                    "If he tries to touch my ass again, I swear I'm going to scream!",
                    "Если он снова попытается потрогать меня, клянусь, я закричу!",
                    OCRBounds(227, 323, 654, 93)
                )
            )
        )
        val output = File(outputPath)
        output.parentFile?.mkdirs()
        assertTrue(ImageIO.write(result, "png", output))
        assertTrue(output.length() > 0)
    }

    @Test
    fun `отрисовка меняет только область текста`() {
        val source = BufferedImage(500, 240, BufferedImage.TYPE_INT_RGB)
        source.createGraphics().apply {
            color = Color(28, 42, 55)
            fillRect(0, 0, source.width, source.height)
            color = Color.WHITE
            fillRoundRect(100, 60, 300, 110, 35, 35)
            color = Color.BLACK
            drawString("Hello world", 170, 120)
            dispose()
        }

        val result = TranslatedImageRenderer.render(
            source,
            listOf(
                TranslatedImageRegion(
                    sourceText = "Hello world",
                    translatedText = "Привет, мир",
                    bounds = OCRBounds(165, 100, 120, 24)
                )
            )
        )

        assertEquals(source.getRGB(20, 20), result.getRGB(20, 20))
        assertNotEquals(source.getRGB(180, 115), result.getRGB(180, 115))
    }
}
