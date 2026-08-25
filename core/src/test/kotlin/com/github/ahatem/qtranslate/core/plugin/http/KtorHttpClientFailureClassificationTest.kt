package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorHttpClientFailureClassificationTest {

    @Test
    fun `все транспортные timeout имеют единую классификацию`() = runTest {
        val failures = listOf<() -> Exception>(
            { HttpRequestTimeoutException("https://api.example.invalid/private?q=secret", 100L, null) },
            { ConnectTimeoutException("connect secret", null) },
            { SocketTimeoutException("socket secret") }
        )

        failures.forEach { failureFactory ->
            val logger = RecordingLogger()
            val engine = MockEngine { throw failureFactory() }
            val result = client(engine, logger).use {
                it.get("https://api.example.invalid/private?q=model-text")
            }

            assertIs<ServiceError.TimeoutError>(result.failure())
            assertTrue(logger.messages.single().contains("host=api.example.invalid"))
            assertTrue(logger.messages.single().contains("type="))
            assertNull(logger.throwables.single())
        }
    }

    @Test
    fun `production лог transport failure не содержит URL body query и message исключения`() = runTest {
        val logger = RecordingLogger()
        val engine = MockEngine { throw IllegalStateException("exception-secret") }
        val result = client(engine, logger).use {
            it.post(
                url = "https://api.example.invalid/private/path?query-secret=value",
                body = "model-body-secret"
            )
        }

        val error = assertIs<ServiceError.NetworkError>(result.failure())
        val observable = (logger.messages + error.message).joinToString("\n")
        assertTrue(observable.contains("host=api.example.invalid"))
        listOf("private/path", "query-secret", "model-body-secret", "exception-secret").forEach { secret ->
            assertFalse(observable.contains(secret), "Секрет попал в диагностику: $secret")
        }
        assertEquals(listOf<Throwable?>(null), logger.throwables)
    }

    @Test
    fun `HTTP error логирует только host status и размер ответа`() = runTest {
        val logger = RecordingLogger()
        val engine = MockEngine {
            respond(content = "response-body-secret", status = HttpStatusCode.InternalServerError)
        }
        val result = client(engine, logger).use {
            it.post("https://api.example.invalid/private?query-secret=value", body = "model-body-secret")
        }

        val error = assertIs<ServiceError.ServiceUnavailableError>(result.failure())
        val observable = (logger.messages + error.message).joinToString("\n")
        assertTrue(observable.contains("host=api.example.invalid"))
        assertTrue(observable.contains("status=500"))
        listOf("private", "query-secret", "model-body-secret", "response-body-secret").forEach { secret ->
            assertFalse(observable.contains(secret), "Секрет попал в HTTP-диагностику: $secret")
        }
    }

    private fun client(engine: MockEngine, logger: RecordingLogger) = KtorHttpClient(
        logger = logger,
        config = HttpClientConfig(enableRetry = false),
        engine = engine,
        ownsEngine = false
    )

    private fun <T> Result<T, ServiceError>.failure(): ServiceError = fold(
        success = { error("Ожидалась ошибка: $it") },
        failure = { it }
    )
}

private class RecordingLogger : Logger {
    val messages = mutableListOf<String>()
    val throwables = mutableListOf<Throwable?>()

    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, error: Throwable?) {
        messages += message
        throwables += error
    }
}
