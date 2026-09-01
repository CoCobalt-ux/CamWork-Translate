package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.michaelbull.result.get
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WindowsOcrServiceTest {
    @Test
    fun `распознает текст локально без API ключа`() = runTest {
        if (!WindowsOcrService.isSupported()) return@runTest

        val image = BufferedImage(900, 220, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().use { graphics ->
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.BLACK
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 72)
            graphics.drawString("HELLO CAMWORK", 40, 140)
        }
        val bytes = ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }

        val response = assertNotNull(WindowsOcrService().extractText(
            OCRRequest(
                image = ImageData(bytes, "png", image.width, image.height),
                language = LanguageCode.ENGLISH
            )
        ).get())

        assertContains(response.text.uppercase(), "HELLO")
        assertContains(response.text.uppercase(), "CAMWORK")
    }

    @Test
    fun `служебный вывод PowerShell не попадает в распознанный текст`() {
        val recognized = WindowsOcrService.extractRecognizedText(
            "WARNING: preparing runtime\n<<<CAMWORK-OCR>>>Hello there\nSecond line"
        )

        assertEquals("Hello there\nSecond line", recognized)
    }

    @Test
    fun `вывод без маркера считается пустым результатом`() {
        assertEquals("", WindowsOcrService.extractRecognizedText("WARNING: nothing recognized"))
    }
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}
