package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GoogleSpellCheckerServiceTest {
    @Test
    fun `первый HTTP 429 останавливает проверку всего текста`() = runTest {
        val http = RecordingClient(Err(ServiceError.RateLimitError("429", retryAfterSeconds = 90)))
        val text = (1..40).joinToString(" ") { "Предложение $it." }

        val result = service(http).check(SpellCheckRequest(text))

        val error = result.fold(success = { error("Ожидался HTTP 429") }, failure = { it })
        assertEquals(90, assertIs<ServiceError.RateLimitError>(error).retryAfterSeconds)
        assertEquals(listOf(Request("Предложение 1.", "auto")), http.requests)
    }

    @Test
    fun `повтор предложения использует кэш с правильным языком и позициями`() = runTest {
        val http = RecordingClient(Ok("""{"spell":{"spell_res":"error.","spell_html_res":"<b><i>error.</i></b>"}}"""))
        val service = service(http)

        val response = service.check(SpellCheckRequest("eror. eror.", LanguageCode.ENGLISH))
            .fold(success = { it }, failure = { error(it.message) })
        assertEquals("error. error.", response.correctedText)
        assertEquals(listOf(0 to 5, 6 to 11), response.corrections.map { it.startIndex to it.endIndex })
        assertEquals(listOf(Request("eror.", "en")), http.requests)

        service.check(SpellCheckRequest("eror.", LanguageCode("de")))
            .fold(success = { it }, failure = { error(it.message) })
        assertEquals(listOf(Request("eror.", "en"), Request("eror.", "de")), http.requests)
    }

    private fun service(http: RecordingClient) = GoogleSpellCheckerService(
        FakePluginContext(dataDirectory = File("."), httpClient = http), http, GoogleLanguageMapper, ApiConfig()
    )

    private data class Request(val text: String, val language: String)

    private class RecordingClient(private val reply: Result<String, ServiceError>) : TextHttpClient() {
        val requests = mutableListOf<Request>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> {
            requests.add(Request(queryParams.getValue("q") as String, queryParams.getValue("sl") as String))
            return reply
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String?,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> = error("Проверка орфографии должна использовать GET")
    }
}
