package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

internal sealed interface GameSubtitleFrameDecision {
    data object Unchanged : GameSubtitleFrameDecision
    data object Clear : GameSubtitleFrameDecision
    data class Translate(val sourceText: String) : GameSubtitleFrameDecision
}

/**
 * Превращает повторяющиеся OCR-снимки в поток сменившихся субтитров.
 * Пустые промежуточные кадры не стирают перевод сразу: это убирает мерцание между кадрами игры.
 */
internal class GameSubtitleFrameTracker(
    private val emptyFramesBeforeClear: Int = DEFAULT_EMPTY_FRAMES_BEFORE_CLEAR
) {
    private var observedText = ""
    private var emptyFrames = 0

    fun observe(rawText: String?): GameSubtitleFrameDecision {
        val normalized = normalizeGameSubtitleText(rawText.orEmpty())
        if (normalized.isBlank()) {
            emptyFrames++
            if (observedText.isNotBlank() && emptyFrames >= emptyFramesBeforeClear) {
                observedText = ""
                return GameSubtitleFrameDecision.Clear
            }
            return GameSubtitleFrameDecision.Unchanged
        }

        emptyFrames = 0
        if (isSameGameSubtitle(normalized, observedText)) {
            // Сохраняем более свежую OCR-форму, но не считаем мелкую поправку новой репликой.
            observedText = normalized
            return GameSubtitleFrameDecision.Unchanged
        }

        observedText = normalized
        return GameSubtitleFrameDecision.Translate(normalized)
    }

    fun reset() {
        observedText = ""
        emptyFrames = 0
    }

    private companion object {
        const val DEFAULT_EMPTY_FRAMES_BEFORE_CLEAR = 7
    }
}

internal fun normalizeGameSubtitleText(value: String): String {
    val normalized = value.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_SUBTITLE_LENGTH)
    return normalized.takeIf { text -> text.count(Char::isLetterOrDigit) >= 2 }.orEmpty()
}

internal fun isSameGameSubtitle(first: String, second: String): Boolean {
    val left = canonicalSubtitle(first)
    val right = canonicalSubtitle(second)
    if (left == right) return true
    if (minOf(left.length, right.length) < MIN_FUZZY_LENGTH) return false

    val longest = maxOf(left.length, right.length)
    if (kotlin.math.abs(left.length - right.length) > (longest * MAX_LENGTH_DELTA_RATIO).toInt()) {
        return false
    }
    return levenshteinDistance(left, right) <= (longest * MAX_EDIT_DISTANCE_RATIO).toInt().coerceAtLeast(1)
}

private fun canonicalSubtitle(value: String): String = normalizeGameSubtitleText(value)
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun levenshteinDistance(first: String, second: String): Int {
    if (first == second) return 0
    if (first.isEmpty()) return second.length
    if (second.isEmpty()) return first.length

    var previous = IntArray(second.length + 1) { it }
    var current = IntArray(second.length + 1)
    first.forEachIndexed { firstIndex, firstChar ->
        current[0] = firstIndex + 1
        second.forEachIndexed { secondIndex, secondChar ->
            current[secondIndex + 1] = minOf(
                current[secondIndex] + 1,
                previous[secondIndex + 1] + 1,
                previous[secondIndex] + if (firstChar == secondChar) 0 else 1
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[second.length]
}

private const val MAX_SUBTITLE_LENGTH = 600
private const val MIN_FUZZY_LENGTH = 10
private const val MAX_LENGTH_DELTA_RATIO = 0.18
private const val MAX_EDIT_DISTANCE_RATIO = 0.12
