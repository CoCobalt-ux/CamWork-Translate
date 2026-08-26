package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwapLanguagesUseCaseTest {
    private val useCase = SwapLanguagesUseCase()

    @Test
    fun `готовый перевод становится вводом и запускается один запрос`() {
        val initial = state(source = LanguageCode.RUSSIAN, target = LanguageCode.ENGLISH)
            .copy(inputText = "Привет", translatedText = "Hello")
        var updated: MainState? = null
        var translations = 0

        useCase(initial, { updated = it }, { translations++ })

        assertEquals(LanguageCode.ENGLISH, updated?.sourceLanguage)
        assertEquals(LanguageCode.RUSSIAN, updated?.targetLanguage)
        assertEquals("Hello", updated?.inputText)
        assertEquals("", updated?.translatedText)
        assertEquals(1, translations)
    }

    @Test
    fun `без готового результата сохраняется ввод и меняется направление`() {
        val initial = state(source = LanguageCode.RUSSIAN, target = LanguageCode.ENGLISH)
            .copy(inputText = "Привет")
        var updated: MainState? = null
        var translations = 0

        useCase(initial, { updated = it }, { translations++ })

        assertEquals("Привет", updated?.inputText)
        assertEquals(LanguageCode.ENGLISH, updated?.sourceLanguage)
        assertEquals(LanguageCode.RUSSIAN, updated?.targetLanguage)
        assertEquals(1, translations)
    }

    @Test
    fun `пустое поле меняет пару без сетевого запроса`() {
        val initial = state(source = LanguageCode.RUSSIAN, target = LanguageCode.ENGLISH)
        var updated: MainState? = null
        var translations = 0

        useCase(initial, { updated = it }, { translations++ })

        assertEquals(LanguageCode.ENGLISH, updated?.sourceLanguage)
        assertEquals(LanguageCode.RUSSIAN, updated?.targetLanguage)
        assertEquals(0, translations)
    }

    @Test
    fun `автоопределение использует определённый язык`() {
        val initial = state(source = LanguageCode.AUTO, target = LanguageCode.ENGLISH)
            .copy(inputText = "Привет", detectedSourceLanguage = LanguageCode.RUSSIAN)
        var updated: MainState? = null

        useCase(initial, { updated = it }, {})

        assertEquals(LanguageCode.ENGLISH, updated?.sourceLanguage)
        assertEquals(LanguageCode.RUSSIAN, updated?.targetLanguage)
        assertTrue(initial.canSwapLanguages())
    }

    @Test
    fun `главное окно не меняет общий текст пока активно временное окно`() {
        val initial = state(source = LanguageCode.UKRAINIAN, target = LanguageCode.ENGLISH).copy(
            inputText = "Hello",
            quickTranslateSourceLanguageOverride = LanguageCode.RUSSIAN,
            quickTranslateTargetLanguageOverride = LanguageCode.GERMAN
        )
        var updates = 0
        var translations = 0

        useCase(initial, { updates++ }, { translations++ })

        assertEquals(0, updates)
        assertEquals(0, translations)
        assertFalse(initial.canSwapLanguages())
    }

    @Test
    fun `быстрое окно с AUTO без определения не подменяет язык из главного окна`() {
        val initial = state(source = LanguageCode.UKRAINIAN, target = LanguageCode.ENGLISH).copy(
            inputText = "123",
            quickTranslateSourceLanguageOverride = LanguageCode.AUTO,
            quickTranslateTargetLanguageOverride = LanguageCode.ENGLISH,
            quickTranslateDetectedLanguageOverride = null
        )
        var updates = 0

        useCase(
            currentState = initial,
            onStateUpdate = { updates++ },
            onTranslateNeeded = {},
            context = SwapLanguagesContext.QUICK_TRANSLATE
        )

        assertEquals(0, updates)
        assertFalse(initial.canSwapLanguages(SwapLanguagesContext.QUICK_TRANSLATE))
    }

    @Test
    fun `автоопределение без известного языка не меняет состояние`() {
        val initial = state(source = LanguageCode.AUTO, target = LanguageCode.ENGLISH)
            .copy(inputText = "Hello")
        var updates = 0
        var translations = 0

        useCase(initial, { updates++ }, { translations++ })

        assertEquals(0, updates)
        assertEquals(0, translations)
        assertFalse(initial.canSwapLanguages())
    }

    @Test
    fun `пассивное окно меняет показанную пару и очищает временные языки`() {
        val initial = state(source = LanguageCode.AUTO, target = LanguageCode.ENGLISH).copy(
            inputText = "Привет",
            translatedText = "Hello",
            quickTranslateSourceLanguageOverride = LanguageCode.RUSSIAN,
            quickTranslateTargetLanguageOverride = LanguageCode.ENGLISH
        )
        var updated: MainState? = null
        var translations = 0

        useCase(
            currentState = initial,
            onStateUpdate = { updated = it },
            onTranslateNeeded = { translations++ },
            context = SwapLanguagesContext.QUICK_TRANSLATE
        )

        assertEquals(LanguageCode.ENGLISH, updated?.sourceLanguage)
        assertEquals(LanguageCode.RUSSIAN, updated?.targetLanguage)
        assertEquals("Hello", updated?.inputText)
        assertEquals(null, updated?.quickTranslateSourceLanguageOverride)
        assertEquals(null, updated?.quickTranslateTargetLanguageOverride)
        assertEquals(1, translations)
        assertTrue(initial.canSwapLanguages(SwapLanguagesContext.QUICK_TRANSLATE))
    }

    private fun state(source: LanguageCode, target: LanguageCode): MainState =
        MainState(sourceLanguage = source, targetLanguage = target)
}
