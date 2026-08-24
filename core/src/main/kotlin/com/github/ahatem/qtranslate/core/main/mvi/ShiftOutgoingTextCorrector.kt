package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import java.util.Locale

/**
 * Быстрый best-effort corrector для частых слов модельного чата.
 *
 * Это не общий spell-checker: он не делает сетевых запросов и меняет только явно внесённые
 * опечатки. Edit-distance здесь намеренно не используется, чтобы не портить корректные слова
 * вроде «ветер», «забота» или «ночь».
 */
internal object ShiftOutgoingTextCorrector {
    fun correct(text: String, modelLanguage: LanguageCode): String {
        val corrections = correctionsFor(modelLanguage) ?: return text
        return CYRILLIC_WORD.replace(text) { match ->
            correctToken(match.value, corrections)
        }
    }

    private fun correctToken(token: String, corrections: Map<String, String>): String {
        val normalized = token.lowercase(Locale.ROOT)
        return corrections[normalized]?.withCaseOf(token) ?: token
    }

    private fun String.withCaseOf(original: String): String = when {
        original.all(Char::isUpperCase) -> uppercase(Locale.ROOT)
        original.firstOrNull()?.isUpperCase() == true ->
            replaceFirstChar { it.titlecase(Locale.ROOT) }
        else -> this
    }

    private fun correctionsFor(language: LanguageCode): Map<String, String>? =
        when (language.tag.substringBefore('-').lowercase(Locale.ROOT)) {
            "ru" -> RUSSIAN_CORRECTIONS
            "uk" -> UKRAINIAN_CORRECTIONS
            else -> null
        }

    private val CYRILLIC_WORD = Regex("[\\p{IsCyrillic}]+")

    private val RUSSIAN_CORRECTIONS = mapOf(
        "привев" to "привет",
        "привт" to "привет",
        "превет" to "привет",
        "приветт" to "привет",
        "спаибо" to "спасибо",
        "спосибо" to "спасибо",
        "спасиба" to "спасибо",
        "пожалуста" to "пожалуйста",
        "пожалуйсто" to "пожалуйста",
        "здраствуйте" to "здравствуйте",
        "севодня" to "сегодня",
        "хоршо" to "хорошо"
    )

    private val UKRAINIAN_CORRECTIONS = mapOf(
        "привит" to "привіт",
        "привт" to "привіт",
        "дякуб" to "дякую",
        "сьогодня" to "сьогодні",
        "пожалуста" to "будь ласка"
    )
}

/** Коррекция разрешена только для уверенного исходящего текста на языке модели. */
internal fun prepareShiftTranslationInput(
    text: String,
    modelLanguage: LanguageCode,
    direction: ShiftSelectionDirection
): String = if (direction == ShiftSelectionDirection.MODEL_LANGUAGE) {
    ShiftOutgoingTextCorrector.correct(text, modelLanguage)
} else {
    text
}
