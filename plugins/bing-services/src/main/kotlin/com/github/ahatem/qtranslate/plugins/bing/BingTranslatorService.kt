package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.toResultOr
import kotlin.math.min

class BingTranslatorService(
    private val pluginContext: PluginContext,
    private val httpClient: HttpClient,
    private val authManager: BingAuthManager,
    private val languageMapper: BingLanguageMapper,
    private val apiConfig: ApiConfig
) : Translator {

    override val key: String = "bing-translator"
    override val name: String = "Bing Translate"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/bing-translate-icon.svg"

    // Bing Translator supports a dynamic set of languages fetched from its API.
    // The core will call fetchSupportedLanguages() once and cache the result.
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        languageMapper.getSupportedLanguages()

    private val parser = createJsonParser<List<BingTranslateResponse>>(pluginContext)

    companion object {
        private const val BING_ORIGIN = "https://www.bing.com"
        private const val TRANSLATOR_REFERER = "$BING_ORIGIN/translator"
        private const val TRANSLATE_URL = "$BING_ORIGIN/ttranslatev3"
        internal const val MAX_CHUNK_LENGTH = 1_000
        private val SENTENCE_ENDINGS = setOf('.', '!', '?', '…', '。', '！', '？')
    }

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
        coroutineBinding {
            val fromLang = languageMapper.toProviderCode(request.sourceLanguage)
            val toLang = languageMapper.toProviderCode(request.targetLanguage)
            val plan = splitForTranslation(request.text)
            if (plan.chunks.isEmpty()) {
                return@coroutineBinding TranslationResponse(translatedText = plan.leadingWhitespace)
            }
            var auth = authManager.getAuth().bind()
            var authRetryAvailable = true
            val translatedText = StringBuilder(request.text.length).append(plan.leadingWhitespace)
            var detectedLanguage: LanguageCode? = null

            for (chunk in plan.chunks) {
                var chunkResult = translateChunk(chunk.text, fromLang, toLang, auth)
                val authenticationFailed = chunkResult.fold(
                    success = { false },
                    failure = { it is ServiceError.AuthenticationError }
                )

                if (authRetryAvailable && authenticationFailed) {
                    authManager.invalidate(auth)
                    auth = authManager.getAuth().bind()
                    authRetryAvailable = false
                    chunkResult = translateChunk(chunk.text, fromLang, toLang, auth)
                }

                val translatedChunk = chunkResult.bind()
                translatedText.append(translatedChunk.text)
                translatedText.append(chunk.separatorAfter)
                if (detectedLanguage == null) {
                    detectedLanguage = translatedChunk.detectedLanguage
                }
            }

            TranslationResponse(
                translatedText = translatedText.toString(),
                detectedLanguage = detectedLanguage
            )
        }

    private suspend fun translateChunk(
        text: String,
        from: String,
        to: String,
        auth: BingAuth
    ): Result<TranslatedChunk, ServiceError> = coroutineBinding {
        val responseString = httpClient.postForm(
            url = TRANSLATE_URL,
            formData = buildFormData(text, from, to, auth),
            headers = buildHeaders(auth),
            queryParams = buildQueryParams(auth),
            cookies = buildCookies(auth)
        ).bind()

        val responses = parser.parse(responseString).bind()
        val response = responses.firstOrNull { !it.translations.isNullOrEmpty() }
            .toResultOr {
                ServiceError.InvalidResponseError("Empty or invalid response from Bing", null)
            }
            .bind()

        TranslatedChunk(
            text = response.translations.orEmpty().joinToString("") { it.text },
            detectedLanguage = response.detectedLanguage
                ?.language
                ?.let(languageMapper::fromProviderCode)
        )
    }

    private fun buildFormData(text: String, from: String, to: String, auth: BingAuth): Map<String, String> =
        mapOf(
            "text" to text,
            "fromLang" to from,
            "to" to to,
            "token" to auth.token,
            "key" to auth.key,
            "isAuthv2" to "true",
            "tryFetchingGenderDebiasedTranslations" to "true"
        )

    private fun buildHeaders(auth: BingAuth): Map<String, String> = apiConfig.createHeaders(
        additionalHeaders = mapOf(
            "User-Agent" to auth.userAgent,
            "Accept" to "application/json, text/plain, */*",
            "Origin" to BING_ORIGIN,
            "Referer" to TRANSLATOR_REFERER,
            "X-Requested-With" to "XMLHttpRequest"
        ),
        randomizeUserAgent = false
    )

    /**
     * GET в текущем HttpClient не возвращает Set-Cookie. Восстанавливаем доступную часть сессии
     * из MUID и SID, которые Bing дублирует в HTML страницы переводчика.
     */
    private fun buildCookies(auth: BingAuth): Map<String, String> = buildMap {
        if (auth.muid.isNotBlank()) {
            put("MUID", auth.muid)
            put("MUIDB", auth.muid)
        }
        if (auth.sid.isNotBlank()) {
            put("_EDGE_S", "F=1&SID=${auth.sid}")
            put("_SS", "SID=${auth.sid}")
        }
    }

    private fun buildQueryParams(auth: BingAuth): Map<String, Any> = mapOf(
        "isVertical" to 1,
        "IG" to auth.ig,
        "IID" to auth.iid
    )

    /**
     * Делит без потери символов. Граничный whitespace хранится отдельно: Bing может обрезать его
     * в ответе, поэтому разделитель восстанавливается локально и не отправляется отдельным чанком.
     */
    internal fun splitForTranslation(text: String): TranslationPlan {
        val contentStart = text.indexOfFirst { !it.isWhitespace() }
        if (contentStart == -1) return TranslationPlan(leadingWhitespace = text, chunks = emptyList())

        val contentEnd = text.indexOfLast { !it.isWhitespace() } + 1
        val leadingWhitespace = text.substring(0, contentStart)
        val trailingWhitespace = text.substring(contentEnd)
        val chunks = mutableListOf<TranslationChunk>()
        var start = contentStart

        while (start < contentEnd) {
            val remaining = contentEnd - start
            if (remaining <= MAX_CHUNK_LENGTH) {
                chunks += TranslationChunk(
                    text = text.substring(start, contentEnd),
                    separatorAfter = trailingWhitespace
                )
                break
            }

            var hardEnd = start + MAX_CHUNK_LENGTH
            if (Character.isHighSurrogate(text[hardEnd - 1]) && Character.isLowSurrogate(text[hardEnd])) {
                hardEnd--
            }
            val minimumBoundary = min(start + MAX_CHUNK_LENGTH / 2, hardEnd)
            val separator = findPreferredSeparator(text, start, minimumBoundary, hardEnd, contentEnd)

            if (separator != null) {
                chunks += TranslationChunk(
                    text = text.substring(start, separator.first),
                    separatorAfter = text.substring(separator.first, separator.last + 1)
                )
                start = separator.last + 1
            } else {
                chunks += TranslationChunk(text = text.substring(start, hardEnd))
                start = hardEnd
            }
        }

        return TranslationPlan(leadingWhitespace, chunks)
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

    private data class TranslatedChunk(
        val text: String,
        val detectedLanguage: LanguageCode?
    )

    internal data class TranslationPlan(
        val leadingWhitespace: String,
        val chunks: List<TranslationChunk>
    )

    internal data class TranslationChunk(
        val text: String,
        val separatorAfter: String = ""
    )
}
