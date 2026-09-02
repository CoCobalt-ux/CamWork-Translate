package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

internal sealed interface GameSubtitleFrameDecision {
    data object Unchanged : GameSubtitleFrameDecision
    data object Clear : GameSubtitleFrameDecision
    data class Translate(
        val sourceText: String,
        val replaceLatest: Boolean = false
    ) : GameSubtitleFrameDecision
}

/**
 * Превращает повторяющиеся OCR-снимки в поток сменившихся субтитров.
 * Пустые промежуточные кадры не стирают перевод сразу: это убирает мерцание между кадрами игры.
 */
internal class GameSubtitleFrameTracker(
    private val emptyFramesBeforeClear: Int = DEFAULT_EMPTY_FRAMES_BEFORE_CLEAR
) {
    private var observedLines = emptyList<String>()
    private var emptyFrames = 0

    fun observe(rawText: String?): GameSubtitleFrameDecision {
        val currentLines = normalizeGameSubtitleLines(rawText.orEmpty())
        if (currentLines.isEmpty()) {
            emptyFrames++
            if (observedLines.isNotEmpty() && emptyFrames >= emptyFramesBeforeClear) {
                observedLines = emptyList()
                return GameSubtitleFrameDecision.Clear
            }
            return GameSubtitleFrameDecision.Unchanged
        }

        emptyFrames = 0
        val currentText = currentLines.joinToString(" ")
        val previousText = observedLines.joinToString(" ")
        if (isSameGameSubtitle(currentText, previousText)) {
            observedLines = currentLines
            return GameSubtitleFrameDecision.Unchanged
        }

        if (observedLines.isEmpty()) {
            observedLines = currentLines
            return GameSubtitleFrameDecision.Translate(currentText)
        }

        val overlap = longestTrailingOverlap(observedLines, currentLines)
        if (overlap > 0) {
            val addedLines = currentLines.drop(overlap)
            observedLines = currentLines
            return if (addedLines.isEmpty()) {
                GameSubtitleFrameDecision.Unchanged
            } else {
                GameSubtitleFrameDecision.Translate(addedLines.joinToString(" "))
            }
        }

        val replaceLatest = isProgressiveSubtitleUpdate(observedLines, currentLines)
        observedLines = currentLines
        return GameSubtitleFrameDecision.Translate(currentText, replaceLatest)
    }

    fun reset() {
        observedLines = emptyList()
        emptyFrames = 0
    }

    private companion object {
        const val DEFAULT_EMPTY_FRAMES_BEFORE_CLEAR = 7
    }
}

internal fun normalizeGameSubtitleLines(value: String): List<String> = value.lineSequence()
    .map(String::trim)
    .map { line -> line.replace(Regex("\\s+"), " ").trim() }
    .filter { line -> line.count(Char::isLetterOrDigit) >= 2 }
    .map { line -> line.take(MAX_SUBTITLE_LENGTH) }
    .toList()

internal fun normalizeGameSubtitleText(value: String): String {
    return normalizeGameSubtitleLines(value)
        .joinToString(" ")
        .take(MAX_SUBTITLE_LENGTH)
}

private fun longestTrailingOverlap(previous: List<String>, current: List<String>): Int {
    val maximum = minOf(previous.size, current.size)
    for (size in maximum downTo 1) {
        val previousStart = previous.size - size
        val matches = (0 until size).all { index ->
            isSameGameSubtitle(previous[previousStart + index], current[index])
        }
        if (matches) return size
    }
    return 0
}

private fun isProgressiveSubtitleUpdate(previous: List<String>, current: List<String>): Boolean {
    if (previous.size != 1 || current.size != 1) return false
    val oldText = canonicalSubtitle(previous.single())
    val newText = canonicalSubtitle(current.single())
    val shorter = if (oldText.length <= newText.length) oldText else newText
    val longer = if (oldText.length > newText.length) oldText else newText
    if (shorter.length < MIN_PROGRESSIVE_LENGTH || !longer.startsWith(shorter)) return false
    return shorter.length.toDouble() / longer.length >= MIN_PROGRESSIVE_COMMON_RATIO
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
private const val MIN_PROGRESSIVE_LENGTH = 4
private const val MIN_PROGRESSIVE_COMMON_RATIO = 0.30
