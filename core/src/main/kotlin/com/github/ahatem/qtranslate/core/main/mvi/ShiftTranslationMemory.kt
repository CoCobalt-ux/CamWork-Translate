package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import java.security.MessageDigest

/**
 * Короткоживущая память двунаправленного Shift-сценария.
 * Хранит только SHA-256 отпечаток перевода, а не пользовательский текст.
 */
internal class ShiftTranslationMemory {
    private data class Replacement(
        val translatedFingerprint: String,
        val detectedSourceLanguage: LanguageCode,
        val configuredModelLanguage: LanguageCode
    )

    private data class PassiveOverlay(
        val sourceFingerprint: String,
        val targetLanguage: LanguageCode,
        val shownAtMillis: Long
    )

    private var lastReplacement: Replacement? = null
    private var lastPassiveOverlay: PassiveOverlay? = null

    fun rememberReplacement(
        translatedText: String,
        detectedSourceLanguage: LanguageCode,
        configuredModelLanguage: LanguageCode
    ) {
        lastReplacement = Replacement(
            translatedFingerprint = fingerprint(translatedText),
            detectedSourceLanguage = detectedSourceLanguage,
            configuredModelLanguage = configuredModelLanguage
        )
    }

    /**
     * Иностранный текст переводится на язык модели, уточнённый последним исходящим переводом.
     *
     * Например, при настройке RU Google может определить исходный кириллический текст как UK.
     * Тогда и точный результат, и следующий произвольный английский текст должны вернуться в UK.
     * Если пользователь после этого явно поменял язык модели в настройках, новая настройка имеет
     * приоритет для произвольного текста; точный предыдущий результат всё ещё возвращается в свой
     * реальный исходный язык.
     */
    fun reverseTargetFor(selectedText: String, fallback: LanguageCode): LanguageCode {
        val replacement = lastReplacement ?: return fallback
        return when {
            replacement.translatedFingerprint == fingerprint(selectedText) ->
                replacement.detectedSourceLanguage
            replacement.configuredModelLanguage == fallback ->
                replacement.detectedSourceLanguage
            else -> fallback
        }
    }

    fun rememberPassiveOverlay(sourceText: String, targetLanguage: LanguageCode, nowMillis: Long) {
        lastPassiveOverlay = PassiveOverlay(fingerprint(sourceText), targetLanguage, nowMillis)
    }

    fun wasRecentlyShown(
        sourceText: String,
        targetLanguage: LanguageCode,
        nowMillis: Long,
        windowMillis: Long
    ): Boolean {
        val overlay = lastPassiveOverlay ?: return false
        return overlay.sourceFingerprint == fingerprint(sourceText) &&
            overlay.targetLanguage == targetLanguage &&
            nowMillis - overlay.shownAtMillis in 0..windowMillis
    }

    private fun fingerprint(text: String): String {
        val normalized = text.trim().replace(WHITESPACE, " ").lowercase()
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
