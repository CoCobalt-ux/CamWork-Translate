package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals

class ShiftSelectionDirectionDetectorTest {
    @Test
    fun `русский текст уверенно относится к языку модели`() {
        assertEquals(
            ShiftSelectionDirection.MODEL_LANGUAGE,
            ShiftSelectionDirectionDetector.detect("Напиши ему завтра утром", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `латинский текст для русской модели считается иностранным`() {
        assertEquals(
            ShiftSelectionDirection.FOREIGN_LANGUAGE,
            ShiftSelectionDirectionDetector.detect("See you tomorrow morning", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `одна кириллическая буква модели переводится в исходящем направлении`() {
        assertEquals(
            ShiftSelectionDirection.MODEL_LANGUAGE,
            ShiftSelectionDirectionDetector.detect("Я", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `цифры и эмодзи не разрешают разрушительную замену`() {
        assertEquals(
            ShiftSelectionDirection.AMBIGUOUS,
            ShiftSelectionDirectionDetector.detect("123 👍", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `смешанный текст без доминирующей письменности неоднозначен`() {
        assertEquals(
            ShiftSelectionDirection.AMBIGUOUS,
            ShiftSelectionDirectionDetector.detect("Привет hello", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `латинский язык модели остаётся безопасно неоднозначным`() {
        assertEquals(
            ShiftSelectionDirection.AMBIGUOUS,
            ShiftSelectionDirectionDetector.detect("Hello model", LanguageCode.ENGLISH)
        )
    }

    @Test
    fun `японская письменность определяется без сети`() {
        assertEquals(
            ShiftSelectionDirection.MODEL_LANGUAGE,
            ShiftSelectionDirectionDetector.detect("こんにちは世界", LanguageCode.JAPANESE)
        )
    }
}
