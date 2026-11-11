package com.github.ahatem.qtranslate.plugins.google


import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.api.spellchecker.CorrectionType
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckResponse
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.google.common.TranslateResponse
import com.github.michaelbull.result.*
import com.github.michaelbull.result.coroutines.coroutineBinding
import java.util.Collections

class GoogleSpellCheckerService(
    private val pluginContext: PluginContext,
    private val httpClient: HttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig
) : SpellChecker {


    override val key: String = "google-spell-checker"
    override val name: String = "Google Spell Checker"
    override val version: String = "1.0.1"
    override val iconPath: String = "assets/google-translate-icon.svg"

    private val parser = createJsonParser<TranslateResponse>(pluginContext)

    private data class SentenceKey(val language: String, val text: String)

    private val cache = Collections.synchronizedMap(object :
        LinkedHashMap<SentenceKey, SpellCheckResponse>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SentenceKey, SpellCheckResponse>?): Boolean {
            return size > 200
        }
    })

    companion object {
        private const val TRANSLATE_PRIMARY = "https://translate.googleapis.com/translate_a/single"
        private val sentenceSeparator = Regex("(?<=[.?!])\\s*")
    }

    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        languageMapper.getSupportedLanguages()

    override suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> {
        if (request.text.isBlank()) {
            return Ok(SpellCheckResponse(request.text, emptyList()))
        }

        val language = languageMapper.toProviderCode(request.language)
        val allCorrections = mutableListOf<Correction>()
        val correctedSentences = mutableListOf<String>()
        var currentOffset = 0

        // Первый HTTP 429 останавливает отправку остальных предложений.
        for (sentence in request.text.split(sentenceSeparator)) {
            if (sentence.isBlank()) continue
            val sentenceOffset = request.text.indexOf(sentence, currentOffset)
            currentOffset = sentenceOffset + sentence.length
            val key = SentenceKey(language, sentence)
            val response = cache[key] ?: checkSentence(sentence, language).fold(
                success = {
                    cache[key] = it
                    it
                },
                failure = { return Err(it) }
            )
            correctedSentences.add(response.correctedText)
            response.corrections.forEach { correction ->
                allCorrections.add(
                    correction.copy(
                        startIndex = sentenceOffset + correction.startIndex,
                        endIndex = sentenceOffset + correction.endIndex
                    )
                )
            }
        }

        return Ok(
            SpellCheckResponse(
                correctedText = correctedSentences.joinToString(" "),
                corrections = allCorrections
            )
        )
    }

    private suspend fun checkSentence(
        sentence: String,
        language: String
    ): Result<SpellCheckResponse, ServiceError> {
        return coroutineBinding {
            val responseString = httpClient.get(
                url = TRANSLATE_PRIMARY,
                headers = apiConfig.createHeaders(),
                queryParams = mapOf(
                    "client" to "gtx",
                    "dj" to 1,
                    "sl" to language,
                    "tl" to "zu",
                    "q" to sentence,
                    "dt" to "qc"
                )
            ).bind()
            parseSpellCheck(responseString, sentence).bind()
        }
    }

    private suspend fun parseSpellCheck(
        responseString: String,
        requestText: String
    ): Result<SpellCheckResponse, ServiceError> {
        return parser.parse(responseString).andThen { translateResponse ->
            val spell = translateResponse.spell
            val correctedText = spell?.correctedText
            val html = spell?.spellHtmlRes

            if (spell == null || correctedText == null || html == null) {
                return@andThen Ok(SpellCheckResponse(correctedText = requestText, corrections = emptyList()))
            }

            val pattern = Regex("<b><i>(.*?)</i></b>")
            val matches = pattern.findAll(html).map { it.groupValues[1] }.toList()

            val corrections = mutableListOf<Correction>()

            if (matches.isNotEmpty()) {
                val correctedWords = correctedText.split(" ")
                val originalWords = requestText.split(" ")

                for (i in correctedWords.indices) {
                    val corrected = correctedWords[i]
                    val original = originalWords.getOrNull(i)
                    if (original != null && original != corrected) {
                        val start = requestText.indexOf(original)
                        if (start > -1) {
                            val end = start + original.length
                            corrections.add(
                                Correction(
                                    original = original,
                                    startIndex = start,
                                    endIndex = end,
                                    suggestions = listOf(corrected),
                                    type = CorrectionType.SPELLING,
                                    message = "Google spell suggestion"
                                )
                            )
                        }
                    }
                }
            }

            Ok(SpellCheckResponse(correctedText = correctedText, corrections = corrections))
        }
    }
}
