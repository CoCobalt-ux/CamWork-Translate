package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals

class ShiftOutgoingTextCorrectorTest {
    @Test
    fun `явная русская опечатка исправляется локально`() {
        assertEquals(
            "Привет",
            ShiftOutgoingTextCorrector.correct("Привев", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `регистр и пунктуация исходной фразы сохраняются`() {
        assertEquals(
            "ПРИВЕТ! Спасибо.",
            ShiftOutgoingTextCorrector.correct("ПРИВЕВ! Спасибо.", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `явная украинская опечатка исправляется своим словарём`() {
        assertEquals(
            "Привіт, дякую",
            ShiftOutgoingTextCorrector.correct("Привіт, дякуб", LanguageCode.UKRAINIAN)
        )
    }

    @Test
    fun `неизвестное слово не исправляется догадкой`() {
        assertEquals(
            "Камворк",
            ShiftOutgoingTextCorrector.correct("Камворк", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `опечатка в имени не исправляется по общему словарю`() {
        assertEquals(
            "Маринп",
            ShiftOutgoingTextCorrector.correct("Маринп", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `несколько равноценных кандидатов не дают разрушительной коррекции`() {
        assertEquals(
            "нужнт",
            ShiftOutgoingTextCorrector.correct("нужнт", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `корректные слова рядом со словарными не портятся`() {
        assertEquals(
            "ветер, забота, ночь",
            ShiftOutgoingTextCorrector.correct("ветер, забота, ночь", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `иностранный язык модели не запускает кириллическую коррекцию`() {
        assertEquals(
            "Привев",
            ShiftOutgoingTextCorrector.correct("Привев", LanguageCode.ENGLISH)
        )
    }


    @Test
    fun `коррекция применяется только к исходящему направлению`() {
        assertEquals(
            "Привет",
            prepareShiftTranslationInput(
                text = "Привев",
                modelLanguage = LanguageCode.RUSSIAN,
                direction = ShiftSelectionDirection.MODEL_LANGUAGE
            )
        )
        assertEquals(
            "Привев",
            prepareShiftTranslationInput(
                text = "Привев",
                modelLanguage = LanguageCode.RUSSIAN,
                direction = ShiftSelectionDirection.FOREIGN_LANGUAGE
            )
        )
    }

    @Test
    fun `Привев исправляется переводится одним вызовом и отправляется на paste`() =
        kotlinx.coroutines.test.runTest {
            var translationCalls = 0
            val pasted = mutableListOf<String>()
            val input = prepareShiftTranslationInput(
                text = "Привев",
                modelLanguage = LanguageCode.RUSSIAN,
                direction = ShiftSelectionDirection.MODEL_LANGUAGE
            )

            val result = executeSelectionTranslation(
                translationInput = input,
                action = SelectionTranslationAction.REPLACE,
                translate = { source ->
                    translationCalls++
                    assertEquals("Привет", source)
                    SelectionTranslationAttempt.Translated("Hello")
                },
                canDeliver = { true },
                onReplace = pasted::add,
                onPassiveOverlay = { error("BIDIRECTIONAL Shift не должен открывать overlay") }
            )

            assertEquals(SelectionTranslationExecution.DELIVERED, result)
            assertEquals(1, translationCalls)
            assertEquals(listOf("Hello"), pasted)
        }
}
