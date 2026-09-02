package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

internal data class GameSubtitleVisualLine(
    val id: Long,
    val text: String,
    val opacity: Float
)

/**
 * Хранит независимые реплики снизу вверх. Новая реплика сокращает время жизни старых,
 * но не меняет их текст и не склеивает их с новым переводом.
 */
internal class GameSubtitleTimeline(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val latestHoldMillis: Long = DEFAULT_LATEST_HOLD_MS,
    private val oldHoldAfterNewMillis: Long = DEFAULT_OLD_HOLD_AFTER_NEW_MS,
    private val fadeMillis: Long = DEFAULT_FADE_MS
) {
    private data class Item(
        val id: Long,
        val text: String,
        var fadeStartsAtMillis: Long,
        var expiresAtMillis: Long
    )

    private val items = mutableListOf<Item>()
    private var sequence = 0L

    fun append(text: String, nowMillis: Long, replaceLatest: Boolean = false) {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return
        prune(nowMillis)

        if (items.isNotEmpty() && (replaceLatest || isSameGameSubtitle(items.last().text, normalized))) {
            val latest = items.last()
            items[items.lastIndex] = latest.copy(
                text = normalized,
                fadeStartsAtMillis = nowMillis + latestHoldMillis,
                expiresAtMillis = nowMillis + latestHoldMillis + fadeMillis
            )
            return
        }

        val oldFadeStart = nowMillis + oldHoldAfterNewMillis
        items.forEach { item ->
            if (item.fadeStartsAtMillis > oldFadeStart) {
                item.fadeStartsAtMillis = oldFadeStart
                item.expiresAtMillis = oldFadeStart + fadeMillis
            }
        }
        items += Item(
            id = ++sequence,
            text = normalized,
            fadeStartsAtMillis = nowMillis + latestHoldMillis,
            expiresAtMillis = nowMillis + latestHoldMillis + fadeMillis
        )
        while (items.size > maxLines) items.removeAt(0)
    }

    fun snapshot(nowMillis: Long): List<GameSubtitleVisualLine> {
        prune(nowMillis)
        val lastIndex = items.lastIndex
        return items.mapIndexed { index, item ->
            val fadeOpacity = when {
                nowMillis <= item.fadeStartsAtMillis -> 1f
                nowMillis >= item.expiresAtMillis -> 0f
                else -> 1f - (nowMillis - item.fadeStartsAtMillis).toFloat() /
                    (item.expiresAtMillis - item.fadeStartsAtMillis).coerceAtLeast(1L)
            }
            val depthOpacity = when (lastIndex - index) {
                0 -> 1f
                1 -> 0.82f
                else -> 0.62f
            }
            GameSubtitleVisualLine(item.id, item.text, minOf(fadeOpacity, depthOpacity))
        }
    }

    fun clear() {
        items.clear()
    }

    private fun prune(nowMillis: Long) {
        items.removeAll { item -> nowMillis >= item.expiresAtMillis }
    }

    private companion object {
        const val DEFAULT_MAX_LINES = 3
        const val DEFAULT_LATEST_HOLD_MS = 8_000L
        const val DEFAULT_OLD_HOLD_AFTER_NEW_MS = 3_200L
        const val DEFAULT_FADE_MS = 1_800L
    }
}
