package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRBounds
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import com.github.ahatem.qtranslate.api.ocr.PositionedOCR
import com.github.ahatem.qtranslate.api.ocr.PositionedOCRResponse
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TranslateImageUseCaseTest {
    @Test
    fun `после ошибки Google переводит найденную область через Bing`() = runTest {
        val google = ImageTestTranslator(
            key = TranslatorFailover.GOOGLE_TRANSLATOR_KEY,
            result = Err(ServiceError.NetworkError("Google недоступен"))
        )
        val bing = ImageTestTranslator(
            key = TranslatorFailover.BING_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Привет", LanguageCode.ENGLISH))
        )
        val preset = ServicePreset(
            id = "image-test",
            name = "image-test",
            selectedServices = mapOf(
                ServiceRole.OCR to "windows-ocr",
                ServiceRole.TRANSLATOR to "google"
            )
        )
        val services = linkedMapOf<String, Service>(
            "windows-ocr" to ImageTestPositionedOcr,
            "google" to google,
            "bing" to bing
        )
        val manager = ActiveServiceManager(
            activeServices = MutableStateFlow(services),
            configuration = MutableStateFlow(
                Configuration(servicePresets = listOf(preset), activeServicePresetId = preset.id)
            )
        )

        val result = TranslateImageUseCase(manager, ImageTestLoggerFactory)(
            image = ImageData(byteArrayOf(1), "png", 200, 80),
            targetLanguage = LanguageCode.RUSSIAN
        )

        val success = assertIs<ImageTranslationRunResult.Success>(result)
        assertEquals("Привет", success.regions.single().translatedText)
        assertEquals(setOf("bing-translator"), success.providers)
        assertEquals(1, google.calls)
        assertEquals(1, bing.calls)
    }

    @Test
    fun `переносы одной реплики объединяются`() {
        val result = mergePositionedTextRegions(
            listOf(
                region("If he tries to touch me again,", 120, 200, 430, 38),
                region("I am going to scream!", 165, 245, 340, 38)
            )
        )

        assertEquals(1, result.size)
        assertEquals("If he tries to touch me again, I am going to scream!", result.single().text)
        assertEquals(OCRBounds(120, 200, 430, 83), result.single().bounds)
    }

    @Test
    fun `разнесенные облака остаются отдельными`() {
        val result = mergePositionedTextRegions(
            listOf(
                region("Hah...", 740, 80, 120, 36),
                region("Main dialogue", 180, 310, 520, 48)
            )
        )

        assertEquals(2, result.size)
    }

    private fun region(text: String, x: Int, y: Int, width: Int, height: Int) =
        OCRTextRegion(text, OCRBounds(x, y, width, height))
}

private object ImageTestPositionedOcr : PositionedOCR {
    override val key: String = "windows-ocr"
    override val name: String = "Windows OCR"
    override val version: String = "test"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> =
        Ok(OCRResponse("Hello"))

    override suspend fun extractPositionedText(
        request: OCRRequest
    ): Result<PositionedOCRResponse, ServiceError> = Ok(
        PositionedOCRResponse(listOf(OCRTextRegion("Hello", OCRBounds(10, 10, 100, 30))))
    )
}

private class ImageTestTranslator(
    override val key: String,
    private val result: Result<TranslationResponse, ServiceError>
) : Translator {
    override val name: String = key
    override val version: String = "test"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All
    var calls: Int = 0
        private set

    override suspend fun translate(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> {
        calls++
        return result
    }
}

private object ImageTestLoggerFactory : LoggerFactory {
    override fun getLogger(name: String): Logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
}
