package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.withTimeoutOrNull
import com.github.ahatem.qtranslate.core.shared.util.shortSummary

class OcrAndTranslateUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("OcrAndTranslateUseCase")

    private companion object {
        const val OCR_TIMEOUT_MS = 30_000L
    }

    /**
     * Performs OCR on [image] and returns the extracted text.
     *
     * Returns an empty string on any failure. The [MainStore] that calls this
     * should update [MainState.inputText] with the result and trigger translation
     * only if the returned string is non-blank.
     */
    suspend operator fun invoke(
        image: ImageData,
        currentState: MainState,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ): String {
        val preferred = activeServiceManager.getActive<OCR>(ServiceRole.OCR)
        val services = buildList {
            preferred?.let(::add)
            activeServiceManager.getAvailable<OCR>(ServiceRole.OCR)
                .filterNot { candidate -> candidate.id == preferred?.id }
                .forEach(::add)
        }
        if (services.isEmpty()) {
            logger.warn("No OCR service available")
            onStatusUpdate(StatusCode.NoOcrServiceActive, NotificationType.ERROR, true)
            return ""
        }

        onStatusUpdate(StatusCode.RecognizingText, NotificationType.INFO, false)
        val request = OCRRequest(image, language = currentState.sourceLanguage)
        logger.debug("OCR request: language=${currentState.sourceLanguage}")

        services.forEachIndexed { index, active ->
            logger.info("Starting OCR with '${active.service.name}'")
            val result = withTimeoutOrNull(OCR_TIMEOUT_MS) {
                active.service.extractText(request)
            }
            if (result == null) {
                logger.warn("OCR '${active.service.name}' timed out after ${OCR_TIMEOUT_MS}ms")
                if (index < services.lastIndex) return@forEachIndexed
                onStatusUpdate(StatusCode.OcrTimeout, NotificationType.ERROR, true)
                return ""
            }

            var response: OCRResponse? = null
            var failure: ServiceError? = null
            result.fold(
                success = { response = it },
                failure = { failure = it }
            )

            failure?.let { error ->
                logger.warn(
                    "OCR '${active.service.name}' failed: errorType=${error::class.simpleName}, " +
                        "causeType=${error.cause?.javaClass?.simpleName ?: "none"}"
                )
                if (index < services.lastIndex) return@forEachIndexed
                onStatusUpdate(
                    StatusCode.OcrFailed(error.shortSummary()),
                    NotificationType.ERROR,
                    true
                )
                return ""
            }

            val recognizedText = response?.text.orEmpty()
            if (recognizedText.isBlank()) {
                logger.warn("No text detected in image by '${active.service.name}'")
                if (index < services.lastIndex) return@forEachIndexed
                onStatusUpdate(StatusCode.NoTextInImage, NotificationType.WARNING, true)
                return ""
            }

            logger.info(
                "OCR successful with '${active.service.name}': detected ${recognizedText.length} characters"
            )
            onStatusUpdate(StatusCode.OcrComplete, NotificationType.SUCCESS, true)
            return recognizedText
        }
        return ""
    }
}
