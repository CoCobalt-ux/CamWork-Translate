package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.fold
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KtorHttpClientRecoveryTest {
    @Test
    fun cancellationAndProviderFailuresDoNotPoisonOneClient() = runBlocking {
        val entered = AtomicInteger()
        val cancellationEntered = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            entered.incrementAndGet()
            when (request.url.encodedPath) {
                "/cancel" -> { cancellationEntered.complete(Unit); delay(10_000); respond("не ожидается") }
                "/network" -> throw java.io.IOException("Тестовый обрыв")
                "/limited" -> respond("", HttpStatusCode.TooManyRequests)
                else -> respond("ok")
            }
        }
        KtorHttpClient(RecoveryLogger, config = HttpClientConfig(enableRetry = false), engine = engine).use { client ->
            val cancelled = async { client.get("http://test.invalid/cancel") }
            withTimeout(5_000) { cancellationEntered.await() }
            cancelled.cancelAndJoin()
            assertTrue(client.get("http://test.invalid/network").isErr)
            assertTrue(client.get("http://test.invalid/limited").isErr)
            val permits = Semaphore(12)
            val results = List(240) { index ->
                async { permits.withPermit { client.get("http://test.invalid/ok?index=" + index) } }
            }.awaitAll()
            assertTrue(results.all { it.isOk })
            assertEquals("ok", client.get("http://test.invalid/last").fold({ it }, { error(it.message) }))
        }
        assertTrue(entered.get() >= 243)
    }

    @Test
    fun cioReleasesConnectionAfterDisconnectAndTimeout() = runBlocking {
        LocalHttpFailureServer().use { server ->
            KtorHttpClient(
                RecoveryLogger,
                config = HttpClientConfig(
                    enableRetry = false, requestTimeoutMillis = 1_000,
                    connectTimeoutMillis = 2_000, socketTimeoutMillis = 2_000,
                    maxConnectionsPerHost = 1, maxConnectionsTotal = 1
                )
            ).use { client ->
                assertTrue(client.get(server.url("disconnect")).isErr)
                assertIs<ServiceError.TimeoutError>(client.get(server.url("timeout")).fold({ error("Ожидался timeout") }, { it }))
                repeat(8) {
                    assertEquals("ok", client.get(server.url("recovered")).fold({ it }, { error(it.message) }))
                }
            }
        }
    }
}

private class LocalHttpFailureServer : Closeable {
    private val listener = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    private val workers = Executors.newCachedThreadPool { action -> Thread(action, "Тест HTTP-восстановления").apply { isDaemon = true } }

    init {
        workers.submit {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets += socket
                workers.submit {
                    socket.use {
                        runCatching {
                            socket.soTimeout = 5_000
                            val reader = socket.getInputStream().bufferedReader()
                            val first = reader.readLine().orEmpty()
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            when {
                                "disconnect" in first -> return@use
                                "timeout" in first -> Thread.sleep(3_000)
                            }
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        }
                    }
                }
            }
        }
    }

    fun url(path: String) = "http://127.0.0.1:" + listener.localPort + "/" + path
    override fun close() {
        listener.close()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        workers.shutdownNow()
    }
}

private object RecoveryLogger : Logger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, error: Throwable?) = Unit
}
