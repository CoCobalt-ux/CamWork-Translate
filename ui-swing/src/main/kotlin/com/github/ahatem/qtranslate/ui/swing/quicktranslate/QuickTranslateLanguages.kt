package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.mvi.MainState

/** Выбранный режим источника и результат автоопределения — разные состояния списка. */
internal data class QuickTranslateLanguages(
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val detectedSourceLanguage: LanguageCode?
)

internal fun MainState.toQuickTranslateLanguages(): QuickTranslateLanguages {
    return QuickTranslateLanguages(
        resolvedQuickTranslateSourceLanguage,
        resolvedQuickTranslateTargetLanguage,
        resolvedQuickTranslateDetectedSourceLanguage
    )
}
