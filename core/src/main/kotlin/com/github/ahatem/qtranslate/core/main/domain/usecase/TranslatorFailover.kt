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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

/** Результат перевода вместе с фактическим сервисом, который его выполнил. */
internal data class TranslationExecution(
    val translator: Translator,
    val translatorId: String,
    val result: Result<TranslationResponse, ServiceError>
)

/**
 * Контролируемая резервная цепочка для основных бесплатных переводчиков.
 *
 * Выбранный пользователем сервис всегда вызывается первым. Если это Google, Bing или DeepL,
 * после его окончательной ошибки пробуются остальные доступные сервисы из той же группы.
 * Каждый id и каждый провайдер вызывается не более одного раза, поэтому цепочка не зацикливается.
 */
internal class TranslatorFailover(
    private val activeServiceManager: ActiveServiceManager,
    private val onEvent: (String) -> Unit = {},
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    suspend fun translate(
        active: ActiveService<Translator>,
        request: TranslationRequest,
        totalTimeoutMillis: Long = DEFAULT_TOTAL_DEADLINE_MS
    ): TranslationExecution {
        val chain = buildChain(active, request)
        val deadlineMillis = monotonicMillis() + totalTimeoutMillis.coerceAtLeast(1L)
        var lastExecution: TranslationExecution? = null

        chain.forEachIndexed { index, candidate ->
            if (index > 0) {
                onEvent(
                    "${lastExecution?.translator?.name ?: active.service.name} недоступен; " +
                        "перевод автоматически передан ${candidate.service.name}"
                )
            }

            val remainingMillis = (deadlineMillis - monotonicMillis()).coerceAtLeast(0L)
            if (remainingMillis == 0L) {
                return TranslationExecution(
                    candidate.service,
                    candidate.id,
                    Err(ServiceError.TimeoutError("Общий deadline перевода исчерпан."))
                )
            }
            val desiredTimeoutMillis = timeoutMillis(candidate.service.key, request.text.length)
                ?: remainingMillis
            val attemptTimeoutMillis = allocateAttemptTimeout(
                desiredTimeoutMillis = desiredTimeoutMillis,
                remainingMillis = remainingMillis,
                laterCandidates = chain.drop(index + 1)
            )
            val result = execute(candidate, request, attemptTimeoutMillis)
            val execution = TranslationExecution(candidate.service, candidate.id, result)
            lastExecution = execution
            if (result.isOk) return execution
        }

        return checkNotNull(lastExecution) { "Цепочка перевода не может быть пустой" }
    }

    private fun buildChain(
        active: ActiveService<Translator>,
        request: TranslationRequest
    ): List<ActiveService<Translator>> {
        if (active.service.key !in MANAGED_PROVIDER_KEYS) return listOf(active)

        val available = activeServiceManager.getAvailable<Translator>(ServiceRole.TRANSLATOR)
        val chain = mutableListOf(active)
        val usedIds = mutableSetOf(active.id)
        val usedProviderKeys = mutableSetOf(active.service.key)

        PROVIDER_ORDER.forEach { serviceKey ->
            val candidate = available.firstOrNull { service ->
                service.id !in usedIds &&
                    service.service.key == serviceKey &&
                    service.service.key !in usedProviderKeys &&
                    service.service.supports(request)
            } ?: return@forEach

            chain += candidate
            usedIds += candidate.id
            usedProviderKeys += candidate.service.key
        }
        return chain
    }

    private suspend fun execute(
        candidate: ActiveService<Translator>,
        request: TranslationRequest,
        timeoutMillis: Long
    ): Result<TranslationResponse, ServiceError> {
        return try {
            withTimeoutOrNull(timeoutMillis.coerceAtLeast(1L)) {
                candidate.service.translate(request)
            } ?: Err(
                ServiceError.TimeoutError(
                    "${candidate.service.name} не ответил за $timeoutMillis мс."
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val errorType = error::class.simpleName ?: error.javaClass.simpleName
            Err(
                ServiceError.UnknownError(
                    "${candidate.service.name}: непредвиденная ошибка type=$errorType"
                )
            )
        }
    }

    /**
     * Ограничивает текущую попытку остатком общего deadline и заранее оставляет минимальное окно
     * каждому следующему провайдеру. Так длинные Google/Bing не съедают шанс третьего fallback.
     */
    private fun allocateAttemptTimeout(
        desiredTimeoutMillis: Long,
        remainingMillis: Long,
        laterCandidates: List<ActiveService<Translator>>
    ): Long {
        if (laterCandidates.isEmpty()) return minOf(desiredTimeoutMillis, remainingMillis).coerceAtLeast(1L)

        val desiredReserve = laterCandidates.sumOf { candidate ->
            minimumAttemptMillis(candidate.service.key) + DEADLINE_HANDOFF_GUARD_MS
        }
        val reserve = desiredReserve.coerceAtMost((remainingMillis - 1L).coerceAtLeast(0L))
        return minOf(desiredTimeoutMillis, remainingMillis - reserve).coerceAtLeast(1L)
    }

    private fun minimumAttemptMillis(serviceKey: String): Long = when (serviceKey) {
        GOOGLE_TRANSLATOR_KEY -> GOOGLE_BASE_TIMEOUT_MS
        BING_TRANSLATOR_KEY -> BING_BASE_TIMEOUT_MS
        DEEPL_TRANSLATOR_KEY -> DEEPL_BASE_TIMEOUT_MS
        else -> CUSTOM_PROVIDER_MINIMUM_CHANCE_MS
    }

    private fun timeoutMillis(serviceKey: String, textLength: Int): Long? = when (serviceKey) {
        GOOGLE_TRANSLATOR_KEY -> scaledTimeout(
            textLength = textLength,
            chunkCharacters = GOOGLE_CHUNK_CHARACTERS,
            baseMillis = GOOGLE_BASE_TIMEOUT_MS,
            additionalChunkMillis = GOOGLE_TIMEOUT_PER_ADDITIONAL_CHUNK_MS,
            maxMillis = GOOGLE_MAX_ATTEMPT_TIMEOUT_MS
        )
        BING_TRANSLATOR_KEY -> scaledTimeout(
            textLength = textLength,
            chunkCharacters = BING_CHUNK_CHARACTERS,
            baseMillis = BING_BASE_TIMEOUT_MS,
            additionalChunkMillis = BING_TIMEOUT_PER_ADDITIONAL_CHUNK_MS,
            maxMillis = BING_MAX_ATTEMPT_TIMEOUT_MS
        )
        DEEPL_TRANSLATOR_KEY -> scaledTimeout(
            textLength = textLength,
            chunkCharacters = DEEPL_CHUNK_CHARACTERS,
            baseMillis = DEEPL_BASE_TIMEOUT_MS,
            additionalChunkMillis = DEEPL_TIMEOUT_PER_ADDITIONAL_CHUNK_MS,
            maxMillis = DEEPL_MAX_ATTEMPT_TIMEOUT_MS
        )
        else -> null
    }

    private fun scaledTimeout(
        textLength: Int,
        chunkCharacters: Int,
        baseMillis: Long,
        additionalChunkMillis: Long,
        maxMillis: Long
    ): Long {
        val chunks = ceil(textLength.coerceAtLeast(1) / chunkCharacters.toDouble()).toLong()
        return (baseMillis + (chunks - 1L) * additionalChunkMillis).coerceAtMost(maxMillis)
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
        internal const val GOOGLE_BASE_TIMEOUT_MS = 5_000L
        internal const val GOOGLE_TIMEOUT_PER_ADDITIONAL_CHUNK_MS = 3_000L
        internal const val GOOGLE_MAX_ATTEMPT_TIMEOUT_MS = 16_000L
        internal const val GOOGLE_CHUNK_CHARACTERS = 4_500
        internal const val BING_BASE_TIMEOUT_MS = 7_000L
        internal const val BING_TIMEOUT_PER_ADDITIONAL_CHUNK_MS = 2_000L
        internal const val BING_MAX_ATTEMPT_TIMEOUT_MS = 20_000L
        internal const val BING_CHUNK_CHARACTERS = 1_000
        internal const val DEEPL_BASE_TIMEOUT_MS = 6_000L
        internal const val DEEPL_TIMEOUT_PER_ADDITIONAL_CHUNK_MS = 3_000L
        internal const val DEEPL_MAX_ATTEMPT_TIMEOUT_MS = 15_000L
        internal const val DEEPL_CHUNK_CHARACTERS = 5_000
        internal const val DEFAULT_TOTAL_DEADLINE_MS = 30_000L
        internal const val DEADLINE_HANDOFF_GUARD_MS = 100L
        internal const val CUSTOM_PROVIDER_MINIMUM_CHANCE_MS = 5_000L

        private val PROVIDER_ORDER = listOf(
            GOOGLE_TRANSLATOR_KEY,
            BING_TRANSLATOR_KEY,
            DEEPL_TRANSLATOR_KEY
        )
        private val MANAGED_PROVIDER_KEYS = PROVIDER_ORDER.toSet()
    }
}
