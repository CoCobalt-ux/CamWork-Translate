package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRBounds
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import com.github.ahatem.qtranslate.api.ocr.PositionedOCR
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

data class TranslatedImageRegion(
    val sourceText: String,
    val translatedText: String,
    val bounds: OCRBounds
)

enum class ImageTranslationFailureKind { OCR_UNAVAILABLE, OCR_FAILED, NO_TEXT, TRANSLATOR_UNAVAILABLE }

sealed interface ImageTranslationRunResult {
    data class Success(
        val regions: List<TranslatedImageRegion>,
        val failedRegions: Int,
        val providers: Set<String>
    ) : ImageTranslationRunResult

    data class Failure(val kind: ImageTranslationFailureKind) : ImageTranslationRunResult
}

/** Одноразовый перевод текста прямо в координатах снимка, независимый от LIVE и истории. */
class TranslateImageUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("TranslateImageUseCase")
    private val failover = TranslatorFailover(activeServiceManager, logger::warn)

    suspend operator fun invoke(
        image: ImageData,
        targetLanguage: LanguageCode,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): ImageTranslationRunResult {
        val positionedServices = activeServiceManager.getAvailable<OCR>(ServiceRole.OCR)
            .mapNotNull { active -> (active.service as? PositionedOCR)?.let { active.id to it } }
        if (positionedServices.isEmpty()) {
            logger.warn("Пространственный OCR недоступен")
            return ImageTranslationRunResult.Failure(ImageTranslationFailureKind.OCR_UNAVAILABLE)
        }

        val request = OCRRequest(image = image, language = LanguageCode.AUTO)
        var recognized: List<OCRTextRegion>? = null
        for ((_, service) in positionedServices) {
            val result = withTimeoutOrNull(OCR_TIMEOUT_MS) { service.extractPositionedText(request) }
                ?: continue
            result.fold(
                success = { response ->
                    if (response.regions.isNotEmpty()) recognized = response.regions
                },
                failure = { error -> logOcrFailure(service.name, error) }
            )
            if (!recognized.isNullOrEmpty()) break
        }

        val regions = recognized
            ?.let(::mergePositionedTextRegions)
            .orEmpty()
            .take(MAX_TRANSLATED_REGIONS)
        if (regions.isEmpty()) {
            return ImageTranslationRunResult.Failure(ImageTranslationFailureKind.NO_TEXT)
        }

        val activeTranslator = activeServiceManager.getActive<Translator>(ServiceRole.TRANSLATOR)
            ?: return ImageTranslationRunResult.Failure(
                ImageTranslationFailureKind.TRANSLATOR_UNAVAILABLE
            )

        val completed = AtomicInteger(0)
        val semaphore = Semaphore(MAX_CONCURRENT_TRANSLATIONS)
        val translations = coroutineScope {
            regions.map { region ->
                async {
                    semaphore.withPermit {
                        val execution = failover.translate(
                            active = activeTranslator,
                            request = TranslationRequest(
                                text = region.text,
                                sourceLanguage = LanguageCode.AUTO,
                                targetLanguage = targetLanguage
                            ),
                            totalTimeoutMillis = REGION_TRANSLATION_TIMEOUT_MS
                        )
                        val translated = execution.result.fold(
                            success = { response -> response.translatedText.trim() to execution.translator.name },
                            failure = { null }
                        )
                        onProgress(completed.incrementAndGet(), regions.size)
                        translated?.let { (text, provider) ->
                            TranslatedRegionResult(
                                region = TranslatedImageRegion(
                                    sourceText = region.text,
                                    translatedText = text.ifBlank { region.text },
                                    bounds = region.bounds
                                ),
                                provider = provider
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        val successful = translations.filterNotNull()
        if (successful.isEmpty()) {
            return ImageTranslationRunResult.Failure(
                ImageTranslationFailureKind.TRANSLATOR_UNAVAILABLE
            )
        }
        return ImageTranslationRunResult.Success(
            regions = successful.map(TranslatedRegionResult::region),
            failedRegions = regions.size - successful.size,
            providers = successful.map(TranslatedRegionResult::provider).toSet()
        )
    }

    private fun logOcrFailure(serviceName: String, error: ServiceError) {
        logger.warn("Пространственный OCR '$serviceName' завершился ошибкой ${error::class.simpleName}")
    }

    private data class TranslatedRegionResult(
        val region: TranslatedImageRegion,
        val provider: String
    )

    private companion object {
        const val OCR_TIMEOUT_MS = 25_000L
        const val REGION_TRANSLATION_TIMEOUT_MS = 18_000L
        const val MAX_CONCURRENT_TRANSLATIONS = 3
        const val MAX_TRANSLATED_REGIONS = 24
    }
}

/** Объединяет переносы внутри одного облака/субтитра, не смешивая соседние реплики. */
internal fun mergePositionedTextRegions(regions: List<OCRTextRegion>): List<OCRTextRegion> {
    val groups = mutableListOf<MutableList<OCRTextRegion>>()
    regions.filter { region -> region.text.isNotBlank() }
        .sortedWith(compareBy<OCRTextRegion> { it.bounds.y }.thenBy { it.bounds.x })
        .forEach { region ->
            val target = groups.asReversed().firstOrNull { group ->
                canMergeImageLines(group, region)
            }
            if (target == null) groups += mutableListOf(region) else target += region
        }

    return groups.map { group ->
        val sorted = group.sortedWith(compareBy<OCRTextRegion> { it.bounds.y }.thenBy { it.bounds.x })
        val left = sorted.minOf { it.bounds.x }
        val top = sorted.minOf { it.bounds.y }
        val right = sorted.maxOf { it.bounds.x + it.bounds.width }
        val bottom = sorted.maxOf { it.bounds.y + it.bounds.height }
        OCRTextRegion(
            text = sorted.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " "),
            bounds = OCRBounds(left, top, right - left, bottom - top),
            confidence = sorted.mapNotNull(OCRTextRegion::confidence).takeIf(List<Float>::isNotEmpty)?.average()?.toFloat()
        )
    }
}

private fun canMergeImageLines(group: List<OCRTextRegion>, candidate: OCRTextRegion): Boolean {
    val left = group.minOf { it.bounds.x }
    val right = group.maxOf { it.bounds.x + it.bounds.width }
    val bottom = group.maxOf { it.bounds.y + it.bounds.height }
    val averageHeight = group.map { it.bounds.height }.average()
    val verticalGap = candidate.bounds.y - bottom
    if (verticalGap !in -4..max(MIN_LINE_GAP, (averageHeight * LINE_GAP_FACTOR).toInt())) return false

    val overlap = min(right, candidate.bounds.x + candidate.bounds.width) - max(left, candidate.bounds.x)
    val narrowerWidth = min(right - left, candidate.bounds.width).coerceAtLeast(1)
    val horizontalOverlap = overlap.coerceAtLeast(0).toDouble() / narrowerWidth
    val center = (left + right) / 2
    val candidateCenter = candidate.bounds.x + candidate.bounds.width / 2
    val centersClose = kotlin.math.abs(center - candidateCenter) <= max(right - left, candidate.bounds.width)
    return horizontalOverlap >= MIN_HORIZONTAL_OVERLAP || centersClose
}

private const val MIN_LINE_GAP = 18
private const val LINE_GAP_FACTOR = 1.15
private const val MIN_HORIZONTAL_OVERLAP = 0.25
