package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode

/** Выбирает безопасную цель, когда автоопределение обнаружило перевод языка в самого себя. */
internal object AutomaticTranslationTargetPolicy {

    fun fallbackForSameDetectedLanguage(
        detectedLanguage: LanguageCode,
        requestedTarget: LanguageCode,
        modelLanguage: LanguageCode?
    ): LanguageCode? {
        val fallback = modelLanguage ?: return null
        if (fallback == LanguageCode.AUTO || requestedTarget == LanguageCode.AUTO) return null
        if (!sameBaseLanguage(detectedLanguage, requestedTarget)) return null
        if (sameBaseLanguage(requestedTarget, fallback)) return null

        return fallback
    }

    private fun sameBaseLanguage(first: LanguageCode, second: LanguageCode): Boolean =
        first.tag.substringBefore('-').equals(second.tag.substringBefore('-'), ignoreCase = true)
}
