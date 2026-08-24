package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode

/** Результат локальной, неразрушительной классификации выделенного текста. */
internal enum class ShiftSelectionDirection {
    MODEL_LANGUAGE,
    FOREIGN_LANGUAGE,
    AMBIGUOUS
}

/**
 * Быстро определяет направление Shift-перевода только по Unicode-письменности.
 *
 * Это намеренно не полноценный language detector: различать русский и украинский либо
 * английский и немецкий без словаря ненадёжно. Если письменность не позволяет принять
 * безопасное решение, результат [ShiftSelectionDirection.AMBIGUOUS] запрещает автозамену.
 */
internal object ShiftSelectionDirectionDetector {
    private const val DOMINANT_SCRIPT_RATIO = 0.70

    fun detect(text: String, modelLanguage: LanguageCode): ShiftSelectionDirection {
        val letters = text.codePoints()
            .filter(Character::isLetter)
            .toArray()
        if (letters.isEmpty()) return ShiftSelectionDirection.AMBIGUOUS

        val modelScripts = scriptsFor(modelLanguage)
        if (modelScripts.isEmpty()) return ShiftSelectionDirection.AMBIGUOUS

        val matching = letters.count { Character.UnicodeScript.of(it) in modelScripts }
        val ratio = matching.toDouble() / letters.size

        return when {
            ratio >= DOMINANT_SCRIPT_RATIO && isDistinctive(modelScripts) ->
                ShiftSelectionDirection.MODEL_LANGUAGE
            ratio <= 1.0 - DOMINANT_SCRIPT_RATIO ->
                ShiftSelectionDirection.FOREIGN_LANGUAGE
            else -> ShiftSelectionDirection.AMBIGUOUS
        }
    }

    /**
     * Латиница используется десятками языков, поэтому совпадение латинской письменности
     * само по себе не даёт права заменять текст. Для неё безопасным остаётся overlay.
     */
    private fun isDistinctive(scripts: Set<Character.UnicodeScript>): Boolean =
        Character.UnicodeScript.LATIN !in scripts

    private fun scriptsFor(language: LanguageCode): Set<Character.UnicodeScript> =
        when (language.tag.substringBefore('-').lowercase()) {
            "ru", "uk", "be", "bg", "mk", "sr", "mn" -> setOf(Character.UnicodeScript.CYRILLIC)
            "ar", "fa", "ur" -> setOf(Character.UnicodeScript.ARABIC)
            "he" -> setOf(Character.UnicodeScript.HEBREW)
            "el" -> setOf(Character.UnicodeScript.GREEK)
            "hy" -> setOf(Character.UnicodeScript.ARMENIAN)
            "ka" -> setOf(Character.UnicodeScript.GEORGIAN)
            "zh" -> setOf(Character.UnicodeScript.HAN)
            "ja" -> setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
            "ko" -> setOf(Character.UnicodeScript.HANGUL)
            "hi", "mr", "ne" -> setOf(Character.UnicodeScript.DEVANAGARI)
            "bn" -> setOf(Character.UnicodeScript.BENGALI)
            "ta" -> setOf(Character.UnicodeScript.TAMIL)
            "te" -> setOf(Character.UnicodeScript.TELUGU)
            "gu" -> setOf(Character.UnicodeScript.GUJARATI)
            "th" -> setOf(Character.UnicodeScript.THAI)
            "my" -> setOf(Character.UnicodeScript.MYANMAR)
            "km" -> setOf(Character.UnicodeScript.KHMER)
            "lo" -> setOf(Character.UnicodeScript.LAO)
            "si" -> setOf(Character.UnicodeScript.SINHALA)
            "am" -> setOf(Character.UnicodeScript.ETHIOPIC)
            "en", "de", "es", "fr", "pt", "id", "sw", "tr", "vi", "it", "jv",
            "ha", "pl", "yo", "nl", "hu", "cs", "sv", "ro", "da", "fi", "no",
            "sk", "sl", "ca", "hr", "ms", "so", "zu", "af", "sq", "az", "eu",
            "bs", "et", "is", "ga", "lv", "lt", "mt", "cy" -> setOf(Character.UnicodeScript.LATIN)
            else -> emptySet()
        }
}
