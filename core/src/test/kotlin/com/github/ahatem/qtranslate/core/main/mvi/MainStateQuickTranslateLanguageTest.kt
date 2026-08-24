package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals

class MainStateQuickTranslateLanguageTest {

    @Test
    fun `обычное окно использует основной язык результата`() {
        val state = MainState(targetLanguage = LanguageCode.ENGLISH)

        assertEquals(LanguageCode.ENGLISH, state.resolvedQuickTranslateTargetLanguage)
    }

    @Test
    fun `автоматический оверлей использует свой временный язык результата`() {
        val state = MainState(
            targetLanguage = LanguageCode.ENGLISH,
            quickTranslateTargetLanguageOverride = LanguageCode.UKRAINIAN
        )

        assertEquals(LanguageCode.UKRAINIAN, state.resolvedQuickTranslateTargetLanguage)
    }
}
