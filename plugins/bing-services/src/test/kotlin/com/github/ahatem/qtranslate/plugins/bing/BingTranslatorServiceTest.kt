package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BingTranslatorServiceTest {

    @Test
    fun `параллельное получение auth выполняет один сетевой запрос`() = runTest {
        val client = RecordingBingHttpClient(
            authPages = listOf(authPage(1)),
            authDelayMillis = 100
        )
        val manager = authManager(client)

        val authResults = coroutineScope {
            List(20) { async { manager.getAuth().success() } }.awaitAll()
        }

        assertEquals(1, client.getHeaders.size)
        assertTrue(authResults.all { it === authResults.first() })
    }

    @Test
    fun `GET и POST используют один User-Agent и полные доступные cookies`() = runTest {
        val client = RecordingBingHttpClient(
            authPages = listOf(authPage(1)),
            postResponder = { call -> Ok(translationResponse(call.formData.getValue("text"))) }
        )
        val apiConfig = ApiConfig(defaultUserAgents = listOf("stable-session-agent", "unused-agent"))
        val service = service(client, apiConfig)

        service.translate(request("Hello")).success()

        val getHeaders = client.getHeaders.single()
        val post = client.formCalls.single()
        assertEquals("stable-session-agent", getHeaders["User-Agent"])
        assertEquals(getHeaders["User-Agent"], post.headers["User-Agent"])
        assertEquals("https://www.bing.com", post.headers["Origin"])
        assertEquals("https://www.bing.com/translator", post.headers["Referer"])
        assertEquals(
            mapOf(
                "MUID" to "muid-1",
                "MUIDB" to "muid-1",
                "_EDGE_S" to "F=1&SID=sid-1",
                "_SS" to "SID=sid-1"
            ),
            post.cookies
        )
    }

    @Test
    fun `auth-отказ инвалидирует сессию и повторяет запрос один раз`() = runTest {
        val responses = mutableListOf<Result<String, ServiceError>>(
            Err(ServiceError.AuthenticationError("expired")),
            Ok(translationResponse("Hallo"))
        )
        val client = RecordingBingHttpClient(
            authPages = listOf(authPage(1), authPage(2)),
            postResponder = { responses.removeFirst() }
        )
        val service = service(client)

        val response = service.translate(request("Hello")).success()

        assertEquals("Hallo", response.translatedText)
        assertEquals(2, client.getHeaders.size)
        assertEquals(2, client.formCalls.size)
        assertEquals(listOf("token-1", "token-2"), client.formCalls.map { it.formData["token"] })
        assertEquals(1, client.getHeaders.map { it["User-Agent"] }.distinct().size)
    }

    @Test
    fun `повторный auth-отказ не запускает третий запрос`() = runTest {
        val client = RecordingBingHttpClient(
            authPages = listOf(authPage(1), authPage(2), authPage(3)),
            postResponder = { Err(ServiceError.AuthenticationError("still expired")) }
        )
        val service = service(client)

        val error = service.translate(request("Hello")).failure()

        assertIs<ServiceError.AuthenticationError>(error)
        assertEquals(2, client.getHeaders.size)
        assertEquals(2, client.formCalls.size)
    }

    @Test
    fun `длинный текст делится до 1000 символов и склеивается без потерь`() = runTest {
        val source = "А".repeat(997) + ". \n" + "🙂" + " слово".repeat(250) + "\nконец"
        val client = RecordingBingHttpClient(
            authPages = listOf(authPage(1)),
            postResponder = { call -> Ok(translationResponse(call.formData.getValue("text").trim())) }
        )
        val service = service(client)

        val response = service.translate(request(source)).success()
        val sentChunks = client.formCalls.map { it.formData.getValue("text") }
        val plan = service.splitForTranslation(source)

        assertEquals(source, response.translatedText)
        assertEquals(source, plan.reconstructedSource())
        assertEquals(plan.chunks.map { it.text }, sentChunks)
        assertTrue(sentChunks.size > 1)
        assertTrue(sentChunks.all { it.length <= BingTranslatorService.MAX_CHUNK_LENGTH })
        assertTrue(sentChunks.none { it.isNotEmpty() && Character.isLowSurrogate(it.first()) })
        assertTrue(sentChunks.none { it.isNotEmpty() && Character.isHighSurrogate(it.last()) })
    }

    @Test
    fun `ровно 1000 символов отправляются одним чанком`() = runTest {
        val source = "A".repeat(1_000)
        val client = RecordingBingHttpClient(
            authPages = listOf(authPage(1)),
            postResponder = { call -> Ok(translationResponse(call.formData.getValue("text"))) }
        )

        val response = service(client).translate(request(source)).success()

        assertEquals(source, response.translatedText)
        assertEquals(listOf(1_000), client.formCalls.map { it.formData.getValue("text").length })
    }

    @Test
    fun `1001 символ делится на чанки 1000 и 1`() = runTest {
        val source = "A".repeat(1_001)
        val client = RecordingBingHttpClient(
            authPages = listOf(authPage(1)),
            postResponder = { call -> Ok(translationResponse(call.formData.getValue("text"))) }
        )

        val response = service(client).translate(request(source)).success()

        assertEquals(source, response.translatedText)
        assertEquals(listOf(1_000, 1), client.formCalls.map { it.formData.getValue("text").length })
    }

    @Test
    fun `граничные пробелы восстанавливаются после trim ответа Bing`() = runTest {
        val separator = " \n\n  "
        val source = "  \n" + "A".repeat(700) + separator + "B".repeat(700) + "\n "
        val client = RecordingBingHttpClient(
            authPages = listOf(authPage(1)),
            postResponder = { call -> Ok(translationResponse(call.formData.getValue("text").trim())) }
        )

        val response = service(client).translate(request(source)).success()

        assertEquals(source, response.translatedText)
        assertEquals(2, client.formCalls.size)
        assertTrue(client.formCalls.none { it.formData.getValue("text").first().isWhitespace() })
        assertTrue(client.formCalls.none { it.formData.getValue("text").last().isWhitespace() })
    }

    @Test
    fun `whitespace-only план не содержит сетевых чанков`() {
        val source = " \n".repeat(700)
        val client = RecordingBingHttpClient(authPages = emptyList())

        val plan = service(client).splitForTranslation(source)

        assertEquals(source, plan.leadingWhitespace)
        assertTrue(plan.chunks.isEmpty())
        assertTrue(client.getHeaders.isEmpty())
        assertTrue(client.formCalls.isEmpty())
    }

    @Test
    fun `длинный whitespace-разделитель не отправляется отдельным запросом`() = runTest {
        val separator = " ".repeat(1_300)
        val source = "A".repeat(700) + separator + "B".repeat(700)
        val client = RecordingBingHttpClient(
            authPages = listOf(authPage(1)),
            postResponder = { call -> Ok(translationResponse(call.formData.getValue("text").trim())) }
        )

        val response = service(client).translate(request(source)).success()

        assertEquals(source, response.translatedText)
        assertEquals(listOf(700, 700), client.formCalls.map { it.formData.getValue("text").length })
        assertTrue(client.formCalls.none { it.formData.getValue("text").isBlank() })
    }

    @Test
    fun `устаревший auth не инвалидирует уже обновлённую сессию`() = runTest {
        val client = RecordingBingHttpClient(authPages = listOf(authPage(1), authPage(2)))
        val manager = authManager(client)
        val oldAuth = manager.getAuth().success()

        manager.invalidate(oldAuth)
        val newAuth = manager.getAuth().success()
        manager.invalidate(oldAuth)

        assertSame(newAuth, manager.getAuth().success())
        assertFalse(oldAuth === newAuth)
        assertEquals(2, client.getHeaders.size)
    }

    private fun service(
        client: RecordingBingHttpClient,
        apiConfig: ApiConfig = testApiConfig()
    ): BingTranslatorService {
        val context = FakePluginContext()
        return BingTranslatorService(
            pluginContext = context,
            httpClient = client,
            authManager = BingAuthManager(context, client, apiConfig),
            languageMapper = BingLanguageMapper,
            apiConfig = apiConfig
        )
    }

    private fun authManager(
        client: RecordingBingHttpClient,
        apiConfig: ApiConfig = testApiConfig()
    ) = BingAuthManager(FakePluginContext(), client, apiConfig)

    private fun request(text: String) = TranslationRequest(
        text = text,
        sourceLanguage = LanguageCode.AUTO,
        targetLanguage = LanguageCode.GERMAN
    )

    private companion object {
        fun testApiConfig() = ApiConfig(defaultUserAgents = listOf("test-session-agent"))

        fun authPage(index: Int): String = """
            <script>var _G={IG:"ig-$index"};</script>
            <main data-iid="translator.$index"></main>
            <script>var params_AbusePreventionHelper = [100$index,"token-$index",3600000];</script>
            <script>var config={"muid":"muid-$index","sid":"sid-$index","tid":"tid-$index"};</script>
        """.trimIndent()

        fun translationResponse(text: String): String = Json.encodeToString(
            listOf(
                BingTranslateResponse(
                    translations = listOf(BingTranslation(text = text, to = "de")),
                    detectedLanguage = BingDetectedLanguage(language = "en")
                )
            )
        )
    }
}

private data class FormCall(
    val formData: Map<String, String>,
    val headers: Map<String, String>,
    val queryParams: Map<String, Any?>,
    val cookies: Map<String, String>
)

private class RecordingBingHttpClient(
    private val authPages: List<String>,
    private val authDelayMillis: Long = 0,
    private val postResponder: (FormCall) -> Result<String, ServiceError> = {
        error("POST form для теста не настроен")
    }
) : TextHttpClient() {
    val getHeaders = mutableListOf<Map<String, String>>()
    val formCalls = mutableListOf<FormCall>()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        val callIndex = getHeaders.size
        getHeaders += headers
        if (authDelayMillis > 0) delay(authDelayMillis)
        return Ok(authPages.getOrElse(callIndex) { authPages.last() })
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = error("Обычный POST для Bing Translator не ожидается")

    override suspend fun postForm(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>,
        cookies: Map<String, String>
    ): Result<String, ServiceError> {
        val call = FormCall(formData, headers, queryParams, cookies)
        formCalls += call
        return postResponder(call)
    }
}

private fun <T> Result<T, ServiceError>.success(): T = fold(
    success = { it },
    failure = { error("Ожидался успех, получено: ${it.message}") }
)

private fun <T> Result<T, ServiceError>.failure(): ServiceError = fold(
    success = { error("Ожидалась ошибка, получен успех: $it") },
    failure = { it }
)

private fun BingTranslatorService.TranslationPlan.reconstructedSource(): String = buildString {
    append(leadingWhitespace)
    chunks.forEach { chunk ->
        append(chunk.text)
        append(chunk.separatorAfter)
    }
}
