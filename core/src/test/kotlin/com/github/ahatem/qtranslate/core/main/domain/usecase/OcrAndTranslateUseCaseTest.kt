package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OcrAndTranslateUseCaseTest {
    @Test
    fun `при ошибке выбранного OCR использует локальный резерв`() = runTest {
        val preferredId = "ai-plugin:default:ai-ocr"
        val fallbackId = "google-services:default:google-ocr"
        val defaultConfig = Configuration.DEFAULT
        val preset = requireNotNull(defaultConfig.getActivePreset()).copy(
            selectedServices = defaultConfig.getActivePreset()!!.selectedServices +
                (ServiceRole.OCR to preferredId)
        )
        val manager = ActiveServiceManager(
            activeServices = MutableStateFlow(
                linkedMapOf(
                    preferredId to FakeOcr(
                        name = "AI Vision OCR",
                        result = Err(ServiceError.ConfigurationError("Ключ не настроен"))
                    ),
                    fallbackId to FakeOcr(
                        name = "Windows OCR",
                        result = Ok(OCRResponse("Hello"))
                    )
                )
            ),
            configuration = MutableStateFlow(defaultConfig.copy(servicePresets = listOf(preset)))
        )
        val statuses = mutableListOf<StatusCode>()

        val text = OcrAndTranslateUseCase(manager, SilentLoggerFactory).invoke(
            image = ImageData(byteArrayOf(1), "png", 1, 1),
            currentState = MainState(),
            onStatusUpdate = { code, _, _ -> statuses += code }
        )

        assertEquals("Hello", text)
        assertTrue(StatusCode.OcrComplete in statuses)
        assertTrue(statuses.none { it is StatusCode.OcrFailed })
    }

    private class FakeOcr(
        override val name: String,
        private val result: Result<OCRResponse, ServiceError>
    ) : OCR {
        override val key: String = "ocr"
        override val version: String = "1.0.0"
        override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

        override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> = result
    }

    private object SilentLoggerFactory : LoggerFactory {
        override fun getLogger(name: String): Logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
    }
}
