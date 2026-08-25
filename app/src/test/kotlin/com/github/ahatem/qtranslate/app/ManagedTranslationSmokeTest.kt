package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationFailureKind
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationRunResult
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedTranslationSmokeTest {
    @Test
    fun `достаточно одного успешного managed provider`() {
        assertTrue(hasSuccessfulManagedTranslation(setOf("bing-services")))
    }

    @Test
    fun `успех необязательного provider не маскирует отказ основной цепочки`() {
        assertFalse(hasSuccessfulManagedTranslation(setOf("mymemory-services")))
        assertFalse(hasSuccessfulManagedTranslation(emptySet()))
    }

    @Test
    fun `packaged smoke принимает реальный перевод managed provider`() {
        requireUsableManagedTranslation(
            TranslationRunResult.Success(
                translatedText = "Сегодня приятная погода.",
                translatorId = "deepl-services:default:deepl-services-translator",
                translatorName = "DeepL"
            )
        )
    }

    @Test
    fun `structured failure больше не считается успешным packaged smoke`() {
        assertFailsWith<IllegalStateException> {
            requireUsableManagedTranslation(
                TranslationRunResult.Failure(TranslationFailureKind.NETWORK)
            )
        }
    }

    @Test
    fun `исходный или пустой ответ не считается переводом`() {
        listOf("", "   ", TRANSLATION_SMOKE_SOURCE_TEXT).forEach { translated ->
            assertFailsWith<IllegalStateException> {
                requireUsableManagedTranslation(
                    TranslationRunResult.Success(
                        translatedText = translated,
                        translatorId = "google-services:default:google-translator",
                        translatorName = "Google Translate"
                    )
                )
            }
        }
    }
}
