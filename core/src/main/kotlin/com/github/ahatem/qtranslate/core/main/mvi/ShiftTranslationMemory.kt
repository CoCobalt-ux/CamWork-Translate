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
        val originalLanguage: LanguageCode
    )

    private data class PassiveOverlay(
        val sourceFingerprint: String,
        val targetLanguage: LanguageCode,
        val shownAtMillis: Long
    )

    @Volatile private var lastReplacement: Replacement? = null
    private var lastPassiveOverlay: PassiveOverlay? = null

    fun rememberReplacement(
        translatedText: String,
        originalLanguage: LanguageCode
    ) {
        lastReplacement = Replacement(
            translatedFingerprint = fingerprint(translatedText),
            originalLanguage = originalLanguage
        )
    }

    /** Язык предыдущего перевода относится только к его собственному результату. */
    fun reverseTargetFor(selectedText: String, fallback: LanguageCode): LanguageCode {
        val replacement = lastReplacement ?: return fallback
        return if (replacement.translatedFingerprint == fingerprint(selectedText)) {
            replacement.originalLanguage
        } else fallback
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
