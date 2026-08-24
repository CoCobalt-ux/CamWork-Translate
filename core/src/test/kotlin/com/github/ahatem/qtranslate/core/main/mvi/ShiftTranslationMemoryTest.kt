package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShiftTranslationMemoryTest {
    @Test
    fun `точный английский результат возвращается в определённый украинский`() {
        val memory = ShiftTranslationMemory()
        memory.rememberReplacement(
            "Good morning!",
            detectedSourceLanguage = LanguageCode.UKRAINIAN,
            configuredModelLanguage = LanguageCode.RUSSIAN
        )

        assertEquals(
            LanguageCode.UKRAINIAN,
            memory.reverseTargetFor("  GOOD   MORNING! ", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `произвольный английский текст возвращается в определённый украинский`() {
        val memory = ShiftTranslationMemory()
        memory.rememberReplacement(
            "Good morning!",
            detectedSourceLanguage = LanguageCode.UKRAINIAN,
            configuredModelLanguage = LanguageCode.RUSSIAN
        )

        assertEquals(
            LanguageCode.UKRAINIAN,
            memory.reverseTargetFor("How are you?", LanguageCode.RUSSIAN)
        )
    }

    @Test
    fun `явно изменённый язык модели важнее старой памяти для произвольного текста`() {
        val memory = ShiftTranslationMemory()
        memory.rememberReplacement(
            "Good morning!",
            detectedSourceLanguage = LanguageCode.RUSSIAN,
            configuredModelLanguage = LanguageCode.RUSSIAN
        )

        assertEquals(
            LanguageCode.UKRAINIAN,
            memory.reverseTargetFor("How are you?", LanguageCode.UKRAINIAN)
        )
    }

    @Test
    fun `без памяти произвольный английский переводится на язык из настроек`() {
        val memory = ShiftTranslationMemory()

        assertEquals(
            LanguageCode.UKRAINIAN,
            memory.reverseTargetFor("How are you?", LanguageCode.UKRAINIAN)
        )
    }

    @Test
    fun `память распознаёт недавний одинаковый overlay`() {
        val memory = ShiftTranslationMemory()
        memory.rememberPassiveOverlay("Hello", LanguageCode.RUSSIAN, nowMillis = 1_000)

        assertTrue(memory.wasRecentlyShown("hello", LanguageCode.RUSSIAN, 2_000, 1_500))
        assertFalse(memory.wasRecentlyShown("hello", LanguageCode.RUSSIAN, 3_000, 1_500))
    }
}
