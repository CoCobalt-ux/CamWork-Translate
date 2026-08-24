package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutomaticTranslationTargetPolicyTest {

    @Test
    fun `английский вместо EN направляется на русский язык модели`() {
        val target = AutomaticTranslationTargetPolicy.fallbackForSameDetectedLanguage(
            detectedLanguage = LanguageCode("en-US"),
            requestedTarget = LanguageCode.ENGLISH,
            modelLanguage = LanguageCode.RUSSIAN
        )

        assertEquals(LanguageCode.RUSSIAN, target)
    }

    @Test
    fun `английский вместо EN направляется на украинский язык модели`() {
        val target = AutomaticTranslationTargetPolicy.fallbackForSameDetectedLanguage(
            detectedLanguage = LanguageCode.ENGLISH,
            requestedTarget = LanguageCode("en-GB"),
            modelLanguage = LanguageCode("uk")
        )

        assertEquals(LanguageCode("uk"), target)
    }

    @Test
    fun `разные исходный и целевой языки не переопределяются`() {
        val target = AutomaticTranslationTargetPolicy.fallbackForSameDetectedLanguage(
            detectedLanguage = LanguageCode("de"),
            requestedTarget = LanguageCode.ENGLISH,
            modelLanguage = LanguageCode.RUSSIAN
        )

        assertNull(target)
    }

    @Test
    fun `язык модели не используется как замена самому себе`() {
        val target = AutomaticTranslationTargetPolicy.fallbackForSameDetectedLanguage(
            detectedLanguage = LanguageCode.RUSSIAN,
            requestedTarget = LanguageCode.RUSSIAN,
            modelLanguage = LanguageCode.RUSSIAN
        )

        assertNull(target)
    }
}
