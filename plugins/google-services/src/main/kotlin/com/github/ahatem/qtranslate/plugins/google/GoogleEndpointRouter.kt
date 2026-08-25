package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/** Быстрая последовательность бесплатных web-endpoint Google с изоляцией отказавших маршрутов. */
internal class GoogleEndpointRouter(
    private val httpClient: HttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val circuitOpenMillis: Long = DEFAULT_CIRCUIT_OPEN_MILLIS,
    private val transientCircuitOpenMillis: Long = DEFAULT_TRANSIENT_CIRCUIT_OPEN_MILLIS,
    private val shortRequestBudgetMillis: Long = DEFAULT_SHORT_BUDGET_MILLIS,
    private val mediumRequestBudgetMillis: Long = DEFAULT_MEDIUM_BUDGET_MILLIS,
    private val longRequestBudgetMillis: Long = DEFAULT_LONG_BUDGET_MILLIS,
    private val shortRouteTimeoutMillis: Long = DEFAULT_SHORT_ROUTE_TIMEOUT_MILLIS,
    private val mediumRouteTimeoutMillis: Long = DEFAULT_MEDIUM_ROUTE_TIMEOUT_MILLIS,
    private val longRouteTimeoutMillis: Long = DEFAULT_LONG_ROUTE_TIMEOUT_MILLIS,
    private val onRouteEvent: (String) -> Unit = {}
) {
    private val blockedUntilMillis = ConcurrentHashMap<Route, Long>()
    private val consecutiveTransientFailures = ConcurrentHashMap<Route, Int>()

    suspend fun translate(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> {
        if (text.length > MAX_BATCH_CHUNK_CHARACTERS) {
            return translateBatchChunks(text, sourceTag, targetTag)
        }

        val isFastRequest = text.length <= MAX_FAST_TEXT_CHARACTERS
        val totalBudget = if (isFastRequest) shortRequestBudgetMillis else mediumRequestBudgetMillis
        val routeTimeout = if (isFastRequest) shortRouteTimeoutMillis else mediumRouteTimeoutMillis
        val startedAt = clockMillis()
        var lastError: ServiceError = ServiceError.ServiceUnavailableError(
            "No Google web endpoint is currently available."
        )

        // translate_a/single сейчас стабильно отвечает 429 даже при исправных соседних endpoint.
        // Не тратим на него пользовательский deadline; enum оставлен для совместимости тестов и
        // возможного контролируемого возврата маршрута после изменения поведения Google.
        val routes = listOf(Route.TRANSLATE_T, Route.BATCH_EXECUTE)
        for (route in routes) {
            val now = clockMillis()
            val blockedUntil = blockedUntilMillis[route] ?: 0L
            if (blockedUntil > now) {
                onRouteEvent("Google route ${route.label} is circuit-open")
                continue
            }

            val remainingBudget = totalBudget - (now - startedAt)
            if (remainingBudget <= 0L) {
                lastError = ServiceError.TimeoutError("Google fast translation budget expired.")
                break
            }

            val result = withTimeoutOrNull(min(routeTimeout, remainingBudget).coerceAtLeast(1L)) {
                request(route, text, sourceTag, targetTag)
            } ?: Err(ServiceError.TimeoutError("Google route ${route.label} timed out."))

            result.fold(
                success = { parsed ->
                    blockedUntilMillis.remove(route)
                    consecutiveTransientFailures.remove(route)
                    onRouteEvent("Google route ${route.label} succeeded")
                    return Ok(parsed.toResponse())
                },
                failure = { error ->
                    lastError = error
                    recordFailure(route, error)
                    onRouteEvent(
                        "Google route ${route.label} failed: type=${error::class.simpleName}"
                    )
                }
            )
        }

        return Err(lastError)
    }

    private suspend fun translateBatchChunks(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> {
        val route = Route.BATCH_EXECUTE
        val now = clockMillis()
        if ((blockedUntilMillis[route] ?: 0L) > now) {
            onRouteEvent("Google route ${route.label} is circuit-open")
            return Err(ServiceError.ServiceUnavailableError("Google batch route is temporarily unavailable."))
        }

        val plan = splitForBatch(text)
        if (plan.chunks.isEmpty()) {
            return Ok(TranslationResponse(translatedText = plan.leadingWhitespace))
        }
        val chunkResults = withTimeoutOrNull(longRequestBudgetMillis) {
            coroutineScope {
                val permits = Semaphore(MAX_PARALLEL_BATCH_REQUESTS)
                plan.chunks.map { chunk ->
                    async {
                        permits.withPermit {
                            withTimeoutOrNull(longRouteTimeoutMillis) {
                                request(route, chunk.text, sourceTag, targetTag)
                            } ?: Err(ServiceError.TimeoutError("Google batch chunk timed out."))
                        }
                    }
                }.awaitAll()
            }
        } ?: return failBatchRoute(ServiceError.TimeoutError("Google long translation budget expired."))

        val translations = ArrayList<GoogleFallbackTranslation>(chunkResults.size)
        chunkResults.forEach { result ->
            result.fold(
                success = translations::add,
                failure = { return failBatchRoute(it) }
            )
        }

        blockedUntilMillis.remove(route)
        consecutiveTransientFailures.remove(route)
        onRouteEvent("Google route ${route.label} succeeded for ${plan.chunks.size} chunks")
        return Ok(
            TranslationResponse(
                translatedText = joinBatchTranslations(plan, translations),
                detectedLanguage = translations.firstNotNullOfOrNull { it.detectedLanguage }
                    ?.let(languageMapper::fromProviderCode)
            )
        )
    }

    private fun failBatchRoute(error: ServiceError): Result<TranslationResponse, ServiceError> {
        recordFailure(Route.BATCH_EXECUTE, error)
        onRouteEvent(
            "Google route ${Route.BATCH_EXECUTE.label} failed: type=${error::class.simpleName}"
        )
        return Err(error)
    }

    private fun recordFailure(route: Route, error: ServiceError) {
        when (error) {
            is ServiceError.RateLimitError -> {
                consecutiveTransientFailures.remove(route)
                val retryAfterMillis = error.retryAfterSeconds?.toLong()?.times(1_000L) ?: 0L
                blockedUntilMillis[route] = clockMillis() + max(circuitOpenMillis, retryAfterMillis)
            }
            is ServiceError.InvalidResponseError,
            is ServiceError.AuthenticationError -> {
                consecutiveTransientFailures.remove(route)
                blockedUntilMillis[route] = clockMillis() + circuitOpenMillis
            }
            is ServiceError.TimeoutError,
            is ServiceError.NetworkError,
            is ServiceError.ServiceUnavailableError -> {
                val failures = consecutiveTransientFailures.merge(route, 1, Int::plus) ?: 1
                if (failures >= TRANSIENT_FAILURES_TO_OPEN) {
                    consecutiveTransientFailures.remove(route)
                    blockedUntilMillis[route] = clockMillis() + transientCircuitOpenMillis
                }
            }
            else -> consecutiveTransientFailures.remove(route)
        }
    }

    /**
     * Делит контент и граничные пробелы отдельно. Endpoint может как сохранить, так и обрезать
     * whitespace, поэтому разделитель не отправляется в Google и добавляется локально ровно один раз.
     */
    internal fun splitForBatch(text: String): BatchPlan {
        val contentStart = text.indexOfFirst { !it.isWhitespace() }
        if (contentStart == -1) return BatchPlan(leadingWhitespace = text, chunks = emptyList())

        val contentEnd = text.indexOfLast { !it.isWhitespace() } + 1
        val leadingWhitespace = text.substring(0, contentStart)
        val trailingWhitespace = text.substring(contentEnd)
        val chunks = mutableListOf<BatchChunk>()
        var start = contentStart

        while (start < contentEnd) {
            val remaining = contentEnd - start
            if (remaining <= MAX_BATCH_CHUNK_CHARACTERS) {
                chunks += BatchChunk(
                    text = text.substring(start, contentEnd),
                    separatorAfter = trailingWhitespace
                )
                break
            }

            var hardEnd = start + MAX_BATCH_CHUNK_CHARACTERS
            if (Character.isHighSurrogate(text[hardEnd - 1]) && Character.isLowSurrogate(text[hardEnd])) {
                hardEnd--
            }
            val minimumBoundary = min(start + MAX_BATCH_CHUNK_CHARACTERS / 2, hardEnd)
            val separator = findPreferredSeparator(text, start, minimumBoundary, hardEnd, contentEnd)

            if (separator != null) {
                chunks += BatchChunk(
                    text = text.substring(start, separator.first),
                    separatorAfter = text.substring(separator.first, separator.last + 1)
                )
                start = separator.last + 1
            } else {
                chunks += BatchChunk(text = text.substring(start, hardEnd))
                start = hardEnd
            }
        }
        return BatchPlan(leadingWhitespace, chunks)
    }

    private fun findPreferredSeparator(
        text: String,
        chunkStart: Int,
        minimum: Int,
        maximum: Int,
        contentEnd: Int
    ): IntRange? {
        val candidates = mutableListOf<IntRange>()
        var cursor = min(maximum, contentEnd - 1)
        while (cursor >= minimum) {
            if (!text[cursor].isWhitespace()) {
                cursor--
                continue
            }

            var start = cursor
            while (start > chunkStart && text[start - 1].isWhitespace()) start--
            var end = cursor + 1
            while (end < contentEnd && text[end].isWhitespace()) end++
            if (start > chunkStart) candidates += start until end
            cursor = start - 1
        }

        return candidates.firstOrNull { range -> range.any { text[it] == '\n' } }
            ?: candidates.firstOrNull { range ->
                range.first > 0 && text[range.first - 1] in SENTENCE_ENDINGS
            }
            ?: candidates.firstOrNull()
    }

    private fun joinBatchTranslations(
        plan: BatchPlan,
        translations: List<GoogleFallbackTranslation>
    ): String = buildString {
        append(plan.leadingWhitespace)
        translations.forEachIndexed { index, translation ->
            append(translation.translatedText)
            append(plan.chunks[index].separatorAfter)
        }
    }

    internal data class BatchPlan(
        val leadingWhitespace: String,
        val chunks: List<BatchChunk>
    )

    internal data class BatchChunk(
        val text: String,
        val separatorAfter: String = ""
    )

    private suspend fun request(
        route: Route,
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<GoogleFallbackTranslation, ServiceError> = when (route) {
        Route.TRANSLATE_T -> httpClient.get(
            url = TRANSLATE_T_ENDPOINT,
            headers = apiConfig.createHeaders(),
            queryParams = mapOf(
                "client" to "dict-chrome-ex",
                "sl" to sourceTag,
                "tl" to targetTag,
                "q" to text
            )
        ).parseWith("translate_a/t", ::parseFallbackTranslationResponse)

        Route.CHROME_SINGLE -> httpClient.get(
            url = CHROME_SINGLE_ENDPOINT,
            headers = apiConfig.createHeaders(),
            queryParams = mapOf(
                "client" to "chrome",
                "sl" to sourceTag,
                "tl" to targetTag,
                "dt" to "t",
                "q" to text
            )
        ).parseWith("translate_a/single", ::parseChromeTranslationResponse)

        Route.BATCH_EXECUTE -> httpClient.postForm(
            url = BATCH_EXECUTE_ENDPOINT,
            formData = mapOf("f.req" to buildBatchRequest(text, sourceTag, targetTag)),
            headers = apiConfig.createHeaders(
                mapOf(
                    "Accept" to "*/*",
                    "Origin" to "https://translate.google.com",
                    "Referer" to "https://translate.google.com/"
                )
            ),
            queryParams = mapOf(
                "rpcids" to "MkEWBc",
                "source-path" to "/",
                "hl" to targetTag,
                "rt" to "c"
            )
        ).parseWith("batchexecute/MkEWBc", ::parseBatchExecuteTranslationResponse)
    }

    private fun GoogleFallbackTranslation.toResponse(): TranslationResponse = TranslationResponse(
        translatedText = translatedText,
        detectedLanguage = detectedLanguage?.let(languageMapper::fromProviderCode)
    )

    private fun buildBatchRequest(text: String, sourceTag: String, targetTag: String): String {
        val rpcArguments = buildJsonArray {
            add(buildJsonArray {
                add(text)
                add(sourceTag)
                add(targetTag)
                add(true)
            })
            add(buildJsonArray { add(JsonNull) })
        }
        return buildJsonArray {
            add(buildJsonArray {
                add(buildJsonArray {
                    add("MkEWBc")
                    add(rpcArguments.toString())
                    add(JsonNull)
                    add("generic")
                })
            })
        }.toString()
    }

    private inline fun Result<String, ServiceError>.parseWith(
        endpointName: String,
        parser: (String) -> GoogleFallbackTranslation?
    ): Result<GoogleFallbackTranslation, ServiceError> = fold(
        success = { body ->
            parser(body)?.let(::Ok)
                ?: Err(ServiceError.InvalidResponseError(
                    "Google $endpointName returned an unsupported response."
                ))
        },
        failure = ::Err
    )

    internal enum class Route(val label: String) {
        TRANSLATE_T("translate_a/t"),
        CHROME_SINGLE("translate_a/single?client=chrome"),
        BATCH_EXECUTE("batchexecute/MkEWBc")
    }

    companion object {
        internal const val TRANSLATE_T_ENDPOINT = "https://clients5.google.com/translate_a/t"
        internal const val CHROME_SINGLE_ENDPOINT = "https://clients5.google.com/translate_a/single"
        internal const val BATCH_EXECUTE_ENDPOINT =
            "https://translate.google.com/_/TranslateWebserverUi/data/batchexecute"

        private const val MAX_FAST_TEXT_CHARACTERS = 800
        internal const val MAX_BATCH_CHUNK_CHARACTERS = 4_500
        private const val MAX_PARALLEL_BATCH_REQUESTS = 2
        private const val TRANSIENT_FAILURES_TO_OPEN = 2
        private val SENTENCE_ENDINGS = setOf('.', '!', '?', '。', '！', '？')
        private const val DEFAULT_CIRCUIT_OPEN_MILLIS = 15 * 60 * 1_000L
        private const val DEFAULT_TRANSIENT_CIRCUIT_OPEN_MILLIS = 30_000L
        private const val DEFAULT_SHORT_BUDGET_MILLIS = 4_000L
        private const val DEFAULT_MEDIUM_BUDGET_MILLIS = 6_000L
        private const val DEFAULT_LONG_BUDGET_MILLIS = 20_000L
        private const val DEFAULT_SHORT_ROUTE_TIMEOUT_MILLIS = 1_800L
        private const val DEFAULT_MEDIUM_ROUTE_TIMEOUT_MILLIS = 2_500L
        private const val DEFAULT_LONG_ROUTE_TIMEOUT_MILLIS = 6_000L
    }
}
