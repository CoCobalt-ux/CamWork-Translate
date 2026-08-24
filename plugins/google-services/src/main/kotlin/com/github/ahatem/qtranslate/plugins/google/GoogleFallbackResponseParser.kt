package com.github.ahatem.qtranslate.plugins.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class GoogleFallbackTranslation(
    val translatedText: String,
    val detectedLanguage: String?
)

private val fallbackJson = Json { isLenient = true }
private val languageTagPattern = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")

/**
 * Разбирает оба известных формата резервного ответа Google:
 * современный `["Bonjour"]` и прежний `[["Bonjour", "en"]]`.
 */
internal fun parseFallbackTranslationResponse(response: String): GoogleFallbackTranslation? =
    runCatching {
        val root = fallbackJson.parseToJsonElement(response) as? JsonArray ?: return@runCatching null
        val fields = when (val first = root.firstOrNull()) {
            is JsonPrimitive -> root
            is JsonArray -> first
            else -> return@runCatching null
        }

        val translatedText = (fields.getOrNull(0) as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return@runCatching null
        val detectedLanguage = (fields.getOrNull(1) as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf(languageTagPattern::matches)

        GoogleFallbackTranslation(
            translatedText = translatedText,
            detectedLanguage = detectedLanguage
        )
    }.getOrNull()

/** Разбирает ответ `translate_a/single?client=chrome`. */
internal fun parseChromeTranslationResponse(response: String): GoogleFallbackTranslation? =
    runCatching {
        val root = fallbackJson.parseToJsonElement(response) as? JsonArray ?: return@runCatching null
        val sentences = root.getOrNull(0) as? JsonArray ?: return@runCatching null
        val translatedText = sentences.mapNotNull { sentence ->
            ((sentence as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.contentOrNull
        }.joinToString("").trim().takeIf(String::isNotEmpty) ?: return@runCatching null
        val detectedLanguage = (root.getOrNull(2) as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf(languageTagPattern::matches)

        GoogleFallbackTranslation(translatedText, detectedLanguage)
    }.getOrNull()

/**
 * Разбирает потоковый ответ Google Web RPC `MkEWBc`.
 *
 * Перед JSON Google добавляет защитный префикс и длину фрейма. Полезный фрейм содержит строкой
 * ещё один JSON-документ, поэтому оба уровня проверяются отдельно. Фиксированные позиции здесь
 * относятся к контракту `MkEWBc`; любое его изменение превращается в ошибку парсинга и безопасно
 * переводит запрос на следующий провайдер.
 */
internal fun parseBatchExecuteTranslationResponse(response: String): GoogleFallbackTranslation? =
    runCatching {
        val frame = response.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("[[") && it.contains("\"MkEWBc\"") }
            ?: return@runCatching null
        val outer = fallbackJson.parseToJsonElement(frame) as? JsonArray ?: return@runCatching null
        val rpcRow = outer.asArrays().firstOrNull { row ->
            row.stringAt(0) == "wrb.fr" && row.stringAt(1) == "MkEWBc"
        } ?: return@runCatching null
        val payload = rpcRow.stringAt(2) ?: return@runCatching null
        val inner = fallbackJson.parseToJsonElement(payload) as? JsonArray ?: return@runCatching null

        val result = inner.getOrNull(1) as? JsonArray ?: return@runCatching null
        val sentenceGroups = result.getOrNull(0) as? JsonArray ?: return@runCatching null
        val translatedText = sentenceGroups.mapNotNull { group ->
            val variants = (group as? JsonArray)?.getOrNull(5) as? JsonArray
            val preferred = variants?.firstOrNull() as? JsonArray
            preferred?.stringAt(0)
        }.joinToString("").trim().takeIf(String::isNotEmpty) ?: return@runCatching null

        val detectedLanguage = result.stringAt(3)
            ?.takeIf(languageTagPattern::matches)
            ?: (inner.getOrNull(0) as? JsonArray)?.stringAt(2)?.takeIf(languageTagPattern::matches)

        GoogleFallbackTranslation(translatedText, detectedLanguage)
    }.getOrNull()

private fun JsonArray.asArrays(): Sequence<JsonArray> =
    asSequence().mapNotNull { it as? JsonArray }

private fun JsonArray.stringAt(index: Int): String? =
    (getOrNull(index) as? JsonPrimitive)?.contentOrNull
