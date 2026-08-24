package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.core.Logger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith

class KtorHttpClientCancellationTest {
    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    @Test
    fun `отмена запроса остаётся отменой и не превращается в NetworkError`() = runBlocking {
        val engine = MockEngine {
            delay(5_000)
            respond(content = "ok", status = HttpStatusCode.OK)
        }
        val client = KtorHttpClient(
            logger = silentLogger,
            config = HttpClientConfig(enableRetry = false),
            engine = engine,
            ownsEngine = false
        )

        client.use {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(50) { it.get("https://example.invalid/slow") }
            }
        }
    }
}
