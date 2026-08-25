package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationRunResult

/** Штатные провайдеры, любой из которых подтверждает работоспособность основной цепочки. */
internal val MANAGED_TRANSLATION_PLUGIN_IDS = setOf(
    "google-services",
    "bing-services",
    "deepl-services"
)

/** Синтетическая фраза smoke-test: пользовательские данные в проверку не попадают. */
internal const val TRANSLATION_SMOKE_SOURCE_TEXT = "The weather is pleasant today."

internal fun hasSuccessfulManagedTranslation(successfulPluginIds: Set<String>): Boolean =
    successfulPluginIds.any(MANAGED_TRANSLATION_PLUGIN_IDS::contains)

/** Отклоняет формальный успех с пустым или фактически непереведённым результатом. */
internal fun requireUsableManagedTranslation(
    result: TranslationRunResult,
    sourceText: String = TRANSLATION_SMOKE_SOURCE_TEXT
): TranslationRunResult.Success {
    val success = checkNotNull(result as? TranslationRunResult.Success) {
        val failure = result as TranslationRunResult.Failure
        "Основная цепочка перевода не прошла smoke-test: ${failure.kind}"
    }
    check(success.translatorId.substringBefore(':') in MANAGED_TRANSLATION_PLUGIN_IDS) {
        "Smoke-test выполнил неожиданный провайдер: ${success.translatorId}"
    }
    check(success.translatedText.isNotBlank()) { "Smoke-test вернул пустой перевод" }
    check(!success.translatedText.trim().equals(sourceText.trim(), ignoreCase = true)) {
        "Smoke-test вернул исходный текст без перевода"
    }
    return success
}
