package com.github.ahatem.qtranslate.ui.swing.livelens

import java.util.Locale

/** Источник текста, который действительно соответствует видимой LIVE-области. */
internal enum class LiveLensInputSource(val logName: String) {
    ACCESSIBILITY("accessibility"),
    PIXEL_OCR("pixel_ocr")
}

internal data class LiveLensInputSelection(
    val messages: List<LiveLensTextBlock>,
    val source: LiveLensInputSource
)

/**
 * Сверяет скрытое accessibility-дерево браузера с текстом на реальном снимке.
 *
 * В обычном чате оба источника почти совпадают, поэтому сохраняются координаты и никнеймы
 * accessibility. В браузерной игре DOM часто содержит десятки невидимых надписей: если они
 * не подтверждаются пикселями, используется только локальный OCR кадра.
 */
internal fun selectLiveLensInput(
    accessibilityMessages: List<LiveLensTextBlock>,
    ocrMessages: List<LiveLensTextBlock>
): LiveLensInputSelection {
    if (ocrMessages.isEmpty()) {
        return LiveLensInputSelection(accessibilityMessages, LiveLensInputSource.ACCESSIBILITY)
    }
    if (accessibilityMessages.isEmpty()) {
        return LiveLensInputSelection(ocrMessages, LiveLensInputSource.PIXEL_OCR)
    }

    val accessibilityTokens = accessibilityMessages.wordTokens()
    val ocrTokens = ocrMessages.wordTokens()
    if (ocrTokens.isEmpty()) {
        return LiveLensInputSelection(accessibilityMessages, LiveLensInputSource.ACCESSIBILITY)
    }
    if (accessibilityTokens.isEmpty()) {
        return LiveLensInputSelection(ocrMessages, LiveLensInputSource.PIXEL_OCR)
    }

    val overlap = accessibilityTokens.intersect(ocrTokens).size.toDouble()
    val accessibilityCoverage = overlap / accessibilityTokens.size
    val ocrCoverage = overlap / ocrTokens.size
    val accessibilityInflation = accessibilityTokens.size.toDouble() / ocrTokens.size
    val accessibilityMatchesPixels =
        accessibilityCoverage >= MIN_ACCESSIBILITY_COVERAGE &&
            ocrCoverage >= MIN_OCR_COVERAGE &&
            accessibilityInflation <= MAX_ACCESSIBILITY_INFLATION

    return if (accessibilityMatchesPixels) {
        LiveLensInputSelection(accessibilityMessages, LiveLensInputSource.ACCESSIBILITY)
    } else {
        LiveLensInputSelection(ocrMessages, LiveLensInputSource.PIXEL_OCR)
    }
}

private fun List<LiveLensTextBlock>.wordTokens(): Set<String> = asSequence()
    .flatMap { block -> sequenceOf(block.speaker.orEmpty(), block.text) }
    .flatMap { text -> WORD.findAll(text).map { match -> match.value.lowercase(Locale.ROOT) } }
    .filter { token -> token.length >= MIN_TOKEN_LENGTH }
    .toSet()

private val WORD = Regex("[\\p{L}\\p{N}]+")
private const val MIN_TOKEN_LENGTH = 2
private const val MIN_ACCESSIBILITY_COVERAGE = 0.55
private const val MIN_OCR_COVERAGE = 0.50
private const val MAX_ACCESSIBILITY_INFLATION = 1.8
