package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.mvi.MainState

/**
 * Меняет исходный и целевой языки местами и при необходимости запускает перевод.
 * Готовый результат становится новым вводом; если результата ещё нет, сохраняется
 * исходный текст, поэтому стрелки работают до первого перевода и после него.
 *
 * This is stateless — no dependencies, pure logic applied to the current [MainState].
 *
 * ### Constraints
 * - Cannot swap if [MainState.sourceLanguage] is [LanguageCode.AUTO] AND no language
 *   has been detected yet — there is no concrete language to swap to.
 *   If a [MainState.detectedSourceLanguage] is available it is used as the new target.
 * - Пустое поле не вызывает бессмысленный сетевой запрос, но выбранная пара языков
 *   всё равно меняется.
 *
 * ### State update ordering
 * [onStateUpdate] is called with the new state before [onTranslateNeeded] is invoked.
 * The caller ([MainStore]) must ensure that [onTranslateNeeded] reads the updated state
 * (i.e. [MainState.inputText] = old translated text) rather than the pre-swap state.
 * Since [MainStore] updates [_state] synchronously in [onStateUpdate] and
 * [onTranslateNeeded] reads [_state.value] asynchronously, this ordering is safe as
 * long as both callbacks are invoked on the same thread/dispatcher.
 */
class SwapLanguagesUseCase {

    operator fun invoke(
        currentState: MainState,
        onStateUpdate: (MainState) -> Unit,
        onTranslateNeeded: () -> Unit,
        context: SwapLanguagesContext = SwapLanguagesContext.MAIN
    ) {
        if (
            context == SwapLanguagesContext.MAIN &&
            (
                currentState.quickTranslateSourceLanguageOverride != null ||
                    currentState.quickTranslateTargetLanguageOverride != null
            )
        ) return
        val effectiveTarget = when (context) {
            SwapLanguagesContext.MAIN -> currentState.targetLanguage
            SwapLanguagesContext.QUICK_TRANSLATE ->
                currentState.quickTranslateTargetLanguageOverride ?: currentState.targetLanguage
        }
        val effectiveSource = when (context) {
            SwapLanguagesContext.MAIN -> currentState.sourceLanguage
                .takeIf { it != LanguageCode.AUTO }
                ?: currentState.detectedSourceLanguage
            SwapLanguagesContext.QUICK_TRANSLATE -> {
                val quickSource = currentState.quickTranslateSourceLanguageOverride
                    ?: currentState.sourceLanguage
                quickSource.takeIf { it != LanguageCode.AUTO }
                    ?: currentState.quickTranslateDetectedLanguageOverride
                    ?: currentState.detectedSourceLanguage.takeIf {
                        currentState.quickTranslateSourceLanguageOverride == null
                    }
            }
        } ?: return
        if (effectiveTarget == LanguageCode.AUTO) return

        val nextInputText = currentState.translatedText.ifBlank { currentState.inputText }

        onStateUpdate(
            currentState.copy(
                sourceLanguage         = effectiveTarget,
                targetLanguage         = effectiveSource,
                inputText              = nextInputText,
                translatedText         = "",
                extraOutputText        = "",
                detectedSourceLanguage = null,
                quickTranslateSourceLanguageOverride = if (
                    context == SwapLanguagesContext.QUICK_TRANSLATE
                ) null else currentState.quickTranslateSourceLanguageOverride,
                quickTranslateTargetLanguageOverride = if (
                    context == SwapLanguagesContext.QUICK_TRANSLATE
                ) null else currentState.quickTranslateTargetLanguageOverride,
                quickTranslateDetectedLanguageOverride = if (
                    context == SwapLanguagesContext.QUICK_TRANSLATE
                ) null else currentState.quickTranslateDetectedLanguageOverride,
                spellCheckCorrections  = emptyList()
            )
        )

        if (nextInputText.isNotBlank()) onTranslateNeeded()
    }
}

/** Единое условие доступности обмена для главного и быстрого окон. */
fun MainState.canSwapLanguages(context: SwapLanguagesContext = SwapLanguagesContext.MAIN): Boolean {
    if (
        context == SwapLanguagesContext.MAIN &&
        (quickTranslateSourceLanguageOverride != null || quickTranslateTargetLanguageOverride != null)
    ) return false
    val target = if (context == SwapLanguagesContext.QUICK_TRANSLATE) {
        quickTranslateTargetLanguageOverride ?: targetLanguage
    } else {
        targetLanguage
    }
    val source = if (context == SwapLanguagesContext.QUICK_TRANSLATE) {
        val quickSource = quickTranslateSourceLanguageOverride ?: sourceLanguage
        quickSource.takeIf { it != LanguageCode.AUTO }
            ?: quickTranslateDetectedLanguageOverride
            ?: detectedSourceLanguage.takeIf { quickTranslateSourceLanguageOverride == null }
    } else {
        sourceLanguage.takeIf { it != LanguageCode.AUTO } ?: detectedSourceLanguage
    }
    return target != LanguageCode.AUTO && source != null
}

enum class SwapLanguagesContext {
    MAIN,
    QUICK_TRANSLATE
}
