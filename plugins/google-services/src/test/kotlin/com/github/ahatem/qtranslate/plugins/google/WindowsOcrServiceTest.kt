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
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsOcrServiceTest {
    @Test
    fun `диагностирует координатный OCR на переданном изображении`() = runTest {
        if (!WindowsOcrService.isSupported()) return@runTest
        val imagePath = System.getenv("CAMWORK_OCR_TEST_IMAGE")
            ?.takeIf(String::isNotBlank)
            ?: return@runTest
        val imageFile = File(imagePath)
        val image = assertNotNull(ImageIO.read(imageFile))
        val response = assertNotNull(
            WindowsOcrService().extractPositionedText(
                OCRRequest(
                    image = ImageData(imageFile.readBytes(), imageFile.extension, image.width, image.height),
                    language = LanguageCode.ENGLISH
                )
            ).get()
        )

        response.regions.forEach { region ->
            println("OCR ${region.bounds}: ${region.text}")
        }
        assertTrue(response.regions.isNotEmpty())
    }

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
    fun `возвращает координаты распознанной строки`() = runTest {
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

        val response = assertNotNull(WindowsOcrService().extractPositionedText(
            OCRRequest(ImageData(bytes, "png", image.width, image.height), LanguageCode.ENGLISH)
        ).get())

        val region = assertNotNull(response.regions.firstOrNull())
        assertContains(region.text.uppercase(), "HELLO")
        assertTrue(region.bounds.width > 100)
        assertTrue(region.bounds.height > 20)
    }

    @Test
    fun `разбирает координаты и base64 текст из протокола`() {
        val encoded = java.util.Base64.getEncoder().encodeToString("Hello world".toByteArray())

        val regions = WindowsOcrService.extractPositionedRegions(
            "warning\n<<<CAMWORK-OCR-LAYOUT>>>\n10\t20\t300\t40\t$encoded"
        )

        assertEquals(1, regions.size)
        assertEquals("Hello world", regions.single().text)
        assertEquals(10, regions.single().bounds.x)
        assertEquals(300, regions.single().bounds.width)
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
