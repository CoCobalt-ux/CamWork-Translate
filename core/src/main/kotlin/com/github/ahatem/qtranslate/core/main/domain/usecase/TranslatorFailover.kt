package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.settings.data.ActiveService
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import kotlinx.coroutines.withTimeoutOrNull

/** Результат перевода вместе с фактическим сервисом, который его выполнил. */
internal data class TranslationExecution(
    val translator: Translator,
    val translatorId: String,
    val result: Result<TranslationResponse, ServiceError>
)

/**
 * Резервная цепочка для основного Google Translator.
 *
 * Выбор другого основного сервиса остаётся решением пользователя: аварийное переключение
 * запускается только после окончательной ошибки Google. Bing пробуется первым как бесплатный
 * сервис без ключа, затем DeepL, который сам выбирает официальный API или бесплатный web-режим.
 */
internal class TranslatorFailover(
    private val activeServiceManager: ActiveServiceManager,
    private val onEvent: (String) -> Unit = {}
) {
    suspend fun translate(
        active: ActiveService<Translator>,
        request: TranslationRequest
    ): TranslationExecution {
        val primaryResult = active.service.translate(request)
        if (primaryResult.isOk || active.service.key != GOOGLE_TRANSLATOR_KEY) {
            return TranslationExecution(active.service, active.id, primaryResult)
        }

        val available = activeServiceManager.getAvailable<Translator>(ServiceRole.TRANSLATOR)
        var lastExecution = TranslationExecution(active.service, active.id, primaryResult)

        FALLBACKS.forEach { fallback ->
            val candidate = available.firstOrNull { service ->
                service.id != active.id &&
                    service.service.key == fallback.serviceKey &&
                    service.service.supports(request)
            } ?: return@forEach

            onEvent("${lastExecution.translator.name} недоступен; перевод автоматически передан ${fallback.displayName}")
            val result = fallback.timeoutMillis?.let { timeoutMillis ->
                withTimeoutOrNull(timeoutMillis) {
                    candidate.service.translate(request)
                } ?: Err(
                    ServiceError.TimeoutError(
                        "${fallback.displayName} did not answer within ${timeoutMillis / 1_000} seconds."
                    )
                )
            } ?: candidate.service.translate(request)
            lastExecution = TranslationExecution(candidate.service, candidate.id, result)
            if (result.isOk) return lastExecution
        }

        return lastExecution
    }

    private fun Translator.supports(request: TranslationRequest): Boolean = when (val supported = supportedLanguages) {
        SupportedLanguages.All, SupportedLanguages.Dynamic -> true
        is SupportedLanguages.Specific ->
            request.sourceLanguage in supported.languages && request.targetLanguage in supported.languages
    }

    companion object {
        internal const val GOOGLE_TRANSLATOR_KEY = "google-translator"
        internal const val BING_TRANSLATOR_KEY = "bing-translator"
        internal const val DEEPL_TRANSLATOR_KEY = "deepl-services-translator"
        internal const val BING_FALLBACK_TIMEOUT_MS = 5_000L

        private val FALLBACKS = listOf(
            Fallback(BING_TRANSLATOR_KEY, "Bing", BING_FALLBACK_TIMEOUT_MS),
            Fallback(DEEPL_TRANSLATOR_KEY, "DeepL")
        )
    }

    private data class Fallback(
        val serviceKey: String,
        val displayName: String,
        val timeoutMillis: Long? = null
    )
}
