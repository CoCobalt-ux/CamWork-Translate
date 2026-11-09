package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickTranslateLanguagesTest {
    @Test
    fun `обычный popup сохраняет AUTO при каждом новом результате определения`() {
        listOf(LanguageCode.ENGLISH, LanguageCode.GERMAN, LanguageCode.UKRAINIAN, LanguageCode.RUSSIAN).forEach { detected ->
            val selection = MainState(sourceLanguage = LanguageCode.AUTO, detectedSourceLanguage = detected)
                .toQuickTranslateLanguages()
            assertEquals(LanguageCode.AUTO, selection.sourceLanguage)
            assertEquals(detected, selection.detectedSourceLanguage)
        }
    }

    @Test
    fun `пассивный popup не берёт устаревшее определение из основного окна`() {
        val selection = MainState(
            sourceLanguage = LanguageCode.AUTO,
            detectedSourceLanguage = LanguageCode.RUSSIAN,
            isQuickTranslateDialogPassive = true,
            quickTranslateSourceLanguageOverride = LanguageCode.AUTO,
            quickTranslateDetectedLanguageOverride = null
        ).toQuickTranslateLanguages()
        assertEquals(LanguageCode.AUTO, selection.sourceLanguage)
        assertNull(selection.detectedSourceLanguage)
    }

    @Test
    fun `пассивный AUTO и его определённый язык остаются отдельными полями`() {
        val selection = MainState(
            sourceLanguage = LanguageCode.UKRAINIAN,
            detectedSourceLanguage = LanguageCode.RUSSIAN,
            quickTranslateSourceLanguageOverride = LanguageCode.AUTO,
            quickTranslateDetectedLanguageOverride = LanguageCode.ENGLISH,
            quickTranslateTargetLanguageOverride = LanguageCode.GERMAN
        ).toQuickTranslateLanguages()
        assertEquals(LanguageCode.AUTO, selection.sourceLanguage)
        assertEquals(LanguageCode.ENGLISH, selection.detectedSourceLanguage)
        assertEquals(LanguageCode.GERMAN, selection.targetLanguage)
    }

    @Test
    fun `явный выбор источника перекрывает старые метаданные определения`() {
        val selection = MainState(
            sourceLanguage = LanguageCode.GERMAN,
            detectedSourceLanguage = LanguageCode.ENGLISH
        ).toQuickTranslateLanguages()
        assertEquals(LanguageCode.GERMAN, selection.sourceLanguage)
        assertNull(selection.detectedSourceLanguage)
    }

    @Test
    fun `язык результата остаётся фактическим и не меняет источник AUTO`() {
        val selection = MainState(
            sourceLanguage = LanguageCode.AUTO,
            targetLanguage = LanguageCode.ENGLISH,
            translatedTargetLanguage = LanguageCode.RUSSIAN,
            detectedSourceLanguage = LanguageCode.ENGLISH
        ).toQuickTranslateLanguages()
        assertEquals(LanguageCode.AUTO, selection.sourceLanguage)
        assertEquals(LanguageCode.RUSSIAN, selection.targetLanguage)
    }
}
