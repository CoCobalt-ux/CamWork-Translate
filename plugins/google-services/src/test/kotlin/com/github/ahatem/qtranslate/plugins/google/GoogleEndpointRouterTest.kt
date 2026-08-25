package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleEndpointRouterTest {

    @Test
    fun `первый быстрый маршрут используется без лишних запросов`() = runBlocking {
        val http = ScriptedHttpClient().apply {
            enqueue(GoogleEndpointRouter.TRANSLATE_T_ENDPOINT, Ok("""[["Hello", "ru"]]"""))
        }
        val router = router(http)

        val response = router.translate("Привет", "auto", "en").success()

        assertEquals("Hello", response.translatedText)
        assertEquals("ru", response.detectedLanguage?.tag)
        assertEquals(listOf(GoogleEndpointRouter.TRANSLATE_T_ENDPOINT), http.calls.map(Call::url))
        assertEquals("dict-chrome-ex", http.calls.single().queryParams["client"])
    }

    @Test
    fun `429 немедленно переключает на batchexecute без запроса single`() = runBlocking {
        val http = ScriptedHttpClient().apply {
            enqueue(
                GoogleEndpointRouter.TRANSLATE_T_ENDPOINT,
                Err(ServiceError.RateLimitError("429"))
            )
            enqueue(GoogleEndpointRouter.BATCH_EXECUTE_ENDPOINT, Ok(batchResponse()))
        }
        val router = router(http)

        val response = router.translate("Привет мир", "auto", "en").success()

        assertEquals("Hello World", response.translatedText)
        assertEquals("ru", response.detectedLanguage?.tag)
        assertEquals(
            listOf(
                GoogleEndpointRouter.TRANSLATE_T_ENDPOINT,
                GoogleEndpointRouter.BATCH_EXECUTE_ENDPOINT
            ),
            http.calls.map(Call::url)
        )
        val batchCall = http.calls.last()
        assertTrue(batchCall.formData.getValue("f.req").contains("MkEWBc"))
        assertEquals("MkEWBc", batchCall.queryParams["rpcids"])
    }

    @Test
    fun `отказавшие маршруты не вызываются до закрытия circuit breaker`() = runBlocking {
        var now = 1_000L
        val http = ScriptedHttpClient().apply {
            repeat(2) {
                enqueue(
                    GoogleEndpointRouter.TRANSLATE_T_ENDPOINT,
                    Err(ServiceError.ServiceUnavailableError("503"))
                )
                enqueue(
                    GoogleEndpointRouter.BATCH_EXECUTE_ENDPOINT,
                    Err(ServiceError.ServiceUnavailableError("503"))
                )
            }
        }
        val router = router(http, clockMillis = { now }, circuitOpenMillis = 900_000L)

        assertIs<ServiceError.ServiceUnavailableError>(router.translate("Привет", "auto", "en").failure())
        assertEquals(2, http.calls.size)

        assertIs<ServiceError.ServiceUnavailableError>(router.translate("Снова", "auto", "en").failure())
        assertEquals(4, http.calls.size)

        assertIs<ServiceError.ServiceUnavailableError>(router.translate("Ещё раз", "auto", "en").failure())
        assertEquals(4, http.calls.size, "Открытый circuit не должен обращаться к сети")

        now += 30_001L
        http.enqueue(GoogleEndpointRouter.TRANSLATE_T_ENDPOINT, Ok("""[["Again", "ru"]]"""))
        val response = router.translate("Снова", "auto", "en").success()

        assertEquals("Again", response.translatedText)
        assertEquals(5, http.calls.size)
    }

    @Test
    fun `один transient отказ не блокирует восстановившийся маршрут`() = runBlocking {
        val http = ScriptedHttpClient().apply {
            enqueue(
                GoogleEndpointRouter.TRANSLATE_T_ENDPOINT,
                Err(ServiceError.TimeoutError("slow"))
            )
            enqueue(GoogleEndpointRouter.BATCH_EXECUTE_ENDPOINT, Ok(batchResponse("Fallback")))
            enqueue(GoogleEndpointRouter.TRANSLATE_T_ENDPOINT, Ok("""[["Recovered", "ru"]]"""))
        }
        val router = router(http)

        assertEquals("Fallback", router.translate("Привет", "auto", "en").success().translatedText)
        assertEquals("Recovered", router.translate("Снова", "auto", "en").success().translatedText)
        assertEquals(
            listOf(
                GoogleEndpointRouter.TRANSLATE_T_ENDPOINT,
                GoogleEndpointRouter.BATCH_EXECUTE_ENDPOINT,
                GoogleEndpointRouter.TRANSLATE_T_ENDPOINT
            ),
            http.calls.map(Call::url)
        )
    }

    @Test
    fun `rate limit сразу открывает долгий circuit только для отказавшего маршрута`() = runBlocking {
        val http = ScriptedHttpClient().apply {
            enqueue(
                GoogleEndpointRouter.TRANSLATE_T_ENDPOINT,
                Err(ServiceError.RateLimitError("429"))
            )
            enqueue(GoogleEndpointRouter.BATCH_EXECUTE_ENDPOINT, Ok(batchResponse("First")))
            enqueue(GoogleEndpointRouter.BATCH_EXECUTE_ENDPOINT, Ok(batchResponse("Second")))
        }
        val router = router(http)

        assertEquals("First", router.translate("Привет", "auto", "en").success().translatedText)
        assertEquals("Second", router.translate("Снова", "auto", "en").success().translatedText)
        assertEquals(
            listOf(
                GoogleEndpointRouter.TRANSLATE_T_ENDPOINT,
                GoogleEndpointRouter.BATCH_EXECUTE_ENDPOINT,
                GoogleEndpointRouter.BATCH_EXECUTE_ENDPOINT
            ),
            http.calls.map(Call::url)
        )
    }

    @Test
    fun `сетевой timeout не отключает Google на пятнадцать минут`() = runBlocking {
        var now = 1_000L
        val http = ScriptedHttpClient().apply {
            enqueue(
                GoogleEndpointRouter.TRANSLATE_T_ENDPOINT,
                Err(ServiceError.TimeoutError("slow"))
            )
            enqueue(
                GoogleEndpointRouter.BATCH_EXECUTE_ENDPOINT,
                Err(ServiceError.TimeoutError("slow"))
            )
        }
        val router = router(http, clockMillis = { now })

        assertIs<ServiceError.TimeoutError>(router.translate("Я".repeat(1_000), "auto", "en").failure())
        assertEquals(2, http.calls.size)

        now += 30_001L
        http.enqueue(GoogleEndpointRouter.TRANSLATE_T_ENDPOINT, Ok("""[["Recovered", "ru"]]"""))
        val response = router.translate("Я".repeat(1_000), "auto", "en").success()

        assertEquals("Recovered", response.translatedText)
        assertEquals(3, http.calls.size)
    }

    @Test
    fun `средний текст сначала использует быстрый translate t`() = runBlocking {
        val http = ScriptedHttpClient().apply {
            enqueue(GoogleEndpointRouter.TRANSLATE_T_ENDPOINT, Ok("""[["Medium", "ru"]]"""))
        }
        val router = router(http)

        val response = router.translate("Я".repeat(2_001), "auto", "en").success()

        assertEquals("Medium", response.translatedText)
        assertEquals(listOf(GoogleEndpointRouter.TRANSLATE_T_ENDPOINT), http.calls.map(Call::url))
    }

    @Test
    fun `средний текст получает увеличенный бюджет вместо ложного timeout`() = runTest {
        val http = ScriptedHttpClient().apply {
            enqueue(GoogleEndpointRouter.TRANSLATE_T_ENDPOINT, Ok("""[["Medium", "ru"]]"""))
            setDelay(GoogleEndpointRouter.TRANSLATE_T_ENDPOINT, 700L)
        }
        val router = router(http, clockMillis = { testScheduler.currentTime })

        val response = router.translate("Я".repeat(1_000), "auto", "en").success()

        assertEquals("Medium", response.translatedText)
        assertEquals(700L, testScheduler.currentTime)
    }

    @Test
    fun `короткий успешный запрос не отменяется на обычной сетевой задержке`() = runTest {
        val http = ScriptedHttpClient().apply {
            enqueue(GoogleEndpointRouter.TRANSLATE_T_ENDPOINT, Ok("""[["Fast", "ru"]]"""))
            setDelay(GoogleEndpointRouter.TRANSLATE_T_ENDPOINT, 1_200L)
        }
        val router = router(http, clockMillis = { testScheduler.currentTime })

        val response = router.translate("Привет", "auto", "en").success()

        assertEquals("Fast", response.translatedText)
        assertEquals(1_200L, testScheduler.currentTime)
    }

    @Test
    fun `длинные чанки переводятся по два параллельно и собираются в исходном порядке`() = runTest {
        val http = ParallelBatchHttpClient()
        val router = GoogleEndpointRouter(
            httpClient = http,
            languageMapper = GoogleLanguageMapper,
            apiConfig = ApiConfig(defaultUserAgents = listOf("test-agent")),
            clockMillis = { testScheduler.currentTime }
        )
        val text = "A".repeat(GoogleEndpointRouter.MAX_BATCH_CHUNK_CHARACTERS) +
            "B".repeat(GoogleEndpointRouter.MAX_BATCH_CHUNK_CHARACTERS) +
            "C".repeat(100)

        val response = router.translate(text, "auto", "en").success()

        assertEquals("FIRSTSECONDTHIRD", response.translatedText)
        assertEquals(3, http.calls)
        assertEquals(2, http.maxActiveCalls)
        assertEquals(2_000L, testScheduler.currentTime, "Три запроса с параллелизмом 2 занимают две волны")
    }

    @Test
    fun `разбиение длинного текста сохраняет все символы и предпочитает границы предложений`() {
        val http = ScriptedHttpClient()
        val router = router(http)
        val firstSentence = "А".repeat(3_000) + ". "
        val text = firstSentence + "Б".repeat(3_000)

        val plan = router.splitForBatch(text)

        assertEquals(text, plan.reconstructedSource())
        assertEquals(firstSentence.trimEnd(), plan.chunks.first().text)
        assertEquals(" ", plan.chunks.first().separatorAfter)
        assertTrue(plan.chunks.all { it.text.length <= GoogleEndpointRouter.MAX_BATCH_CHUNK_CHARACTERS })
    }

    @Test
    fun `склейка переведённых чанков восстанавливает пробел исходной границы`() = runTest {
        val http = ParallelBatchHttpClient()
        val router = GoogleEndpointRouter(
            httpClient = http,
            languageMapper = GoogleLanguageMapper,
            apiConfig = ApiConfig(defaultUserAgents = listOf("test-agent")),
            clockMillis = { testScheduler.currentTime }
        )
        val text = "A".repeat(3_000) + ". " + "B".repeat(3_000)

        val response = router.translate(text, "auto", "en").success()

        assertEquals("FIRST SECOND", response.translatedText)
    }

    @Test
    fun `boundary whitespace не отправляется в Google и добавляется ровно один раз`() = runTest {
        val http = ParallelBatchHttpClient()
        val router = GoogleEndpointRouter(
            httpClient = http,
            languageMapper = GoogleLanguageMapper,
            apiConfig = ApiConfig(defaultUserAgents = listOf("test-agent")),
            clockMillis = { testScheduler.currentTime }
        )
        val first = "A".repeat(3_000) + "."
        val separator = " \n\n "
        val text = "  " + first + separator + "B".repeat(3_000) + "  "

        val response = router.translate(text, "auto", "en").success()

        assertEquals("  FIRST${separator}SECOND  ", response.translatedText)
        assertTrue(http.payloads.none { it.contains(first + " ") })
    }

    @Test
    fun `длинный whitespace-only текст возвращается без сетевого запроса`() = runTest {
        val http = ParallelBatchHttpClient()
        val router = GoogleEndpointRouter(
            httpClient = http,
            languageMapper = GoogleLanguageMapper,
            apiConfig = ApiConfig(defaultUserAgents = listOf("test-agent")),
            clockMillis = { testScheduler.currentTime }
        )
        val text = " \n".repeat(3_000)

        val response = router.translate(text, "auto", "en").success()

        assertEquals(text, response.translatedText)
        assertEquals(0, http.calls)
    }

    @Test
    fun `route telemetry не содержит message ошибки провайдера`() = runBlocking {
        val events = mutableListOf<String>()
        val http = ScriptedHttpClient().apply {
            enqueue(
                GoogleEndpointRouter.TRANSLATE_T_ENDPOINT,
                Err(ServiceError.NetworkError("model-text-secret"))
            )
            enqueue(GoogleEndpointRouter.BATCH_EXECUTE_ENDPOINT, Ok(batchResponse()))
        }
        val router = GoogleEndpointRouter(
            httpClient = http,
            languageMapper = GoogleLanguageMapper,
            apiConfig = ApiConfig(defaultUserAgents = listOf("test-agent")),
            onRouteEvent = events::add
        )

        router.translate("Привет", "auto", "en").success()

        assertTrue(events.none { it.contains("model-text-secret") })
        assertTrue(events.any { it.contains("type=NetworkError") })
    }

    private fun router(
        http: ScriptedHttpClient,
        clockMillis: () -> Long = System::currentTimeMillis,
        circuitOpenMillis: Long = 900_000L
    ) = GoogleEndpointRouter(
        httpClient = http,
        languageMapper = GoogleLanguageMapper,
        apiConfig = ApiConfig(defaultUserAgents = listOf("test-agent")),
        clockMillis = clockMillis,
        circuitOpenMillis = circuitOpenMillis
    )

    private fun <T> Result<T, ServiceError>.success(): T = fold(
        success = { it },
        failure = { error("Ожидался успех, получено: ${it.message}") }
    )

    private fun <T> Result<T, ServiceError>.failure(): ServiceError = fold(
        success = { error("Ожидалась ошибка, получен успех: $it") },
        failure = { it }
    )
}

private data class Call(
    val method: String,
    val url: String,
    val queryParams: Map<String, Any?>,
    val formData: Map<String, String> = emptyMap()
)

private class ScriptedHttpClient : TextHttpClient() {
    private val scripted = mutableMapOf<String, ArrayDeque<Result<String, ServiceError>>>()
    private val delays = mutableMapOf<String, Long>()
    val calls = mutableListOf<Call>()

    fun enqueue(url: String, result: Result<String, ServiceError>) {
        scripted.getOrPut(url, ::ArrayDeque).addLast(result)
    }

    fun setDelay(url: String, millis: Long) {
        delays[url] = millis
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        calls += Call("GET", url, queryParams)
        delays[url]?.let { delay(it) }
        return next(url)
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = error("POST не ожидался: $url")

    override suspend fun postForm(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>,
        cookies: Map<String, String>
    ): Result<String, ServiceError> {
        calls += Call("POST_FORM", url, queryParams, formData)
        delays[url]?.let { delay(it) }
        return next(url)
    }

    private fun next(url: String): Result<String, ServiceError> =
        scripted[url]?.removeFirstOrNull() ?: error("Для $url не подготовлен ответ")
}

private class ParallelBatchHttpClient : TextHttpClient() {
    var calls = 0
        private set
    val payloads = mutableListOf<String>()
    var maxActiveCalls = 0
        private set
    private var activeCalls = 0

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = error("GET не ожидался: $url")

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = error("POST не ожидался: $url")

    override suspend fun postForm(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>,
        cookies: Map<String, String>
    ): Result<String, ServiceError> {
        calls++
        payloads += formData.getValue("f.req")
        activeCalls++
        maxActiveCalls = maxOf(maxActiveCalls, activeCalls)
        val payload = formData.getValue("f.req")
        val translated = when {
            payload.contains("A".repeat(100)) -> "FIRST"
            payload.contains("B".repeat(100)) -> "SECOND"
            else -> "THIRD"
        }
        delay(1_000L)
        activeCalls--
        return Ok(batchResponse(translated))
    }
}

private fun GoogleEndpointRouter.BatchPlan.reconstructedSource(): String = buildString {
    append(leadingWhitespace)
    chunks.forEach { chunk ->
        append(chunk.text)
        append(chunk.separatorAfter)
    }
}

private fun batchResponse(translatedText: String = "Hello World"): String = """)]}\'

405
[["wrb.fr","MkEWBc","[[null,null,\"ru\",[[[0,[[[null,10]],[true]]]],10],null,null,[\"Привет мир\",\"auto\",\"en\",true]],[[[null,null,null,null,null,[[\"$translatedText\",null,null,null,null,null,\"Привет мир\",1]],null,null,null,[]]],\"en\",1,\"ru\",[\"Привет мир\",\"auto\",\"en\",true]],\"ru\",null,null,null,null,[[[0]]]]",null,null,null,"generic"]]
"""
