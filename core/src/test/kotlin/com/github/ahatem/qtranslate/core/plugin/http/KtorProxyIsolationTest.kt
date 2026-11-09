package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.michaelbull.result.fold
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.http
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Сквозная регрессия контракта CIO: проверяет реальные байты CONNECT и запроса внутри TLS. */
class KtorProxyIsolationTest {
    @Test
    fun httpProxyKeepsAuthentication() = runBlocking {
        ProxyServer().use { proxy ->
            KtorHttpClient(ProxyLogger, config = config(proxy)).use { client ->
                assertEquals("ok", client.get("http://origin.invalid/plain").fold({ it }, { error(it.message) }))
            }
            val request = proxy.requests.single()
            assertEquals(null, request.connect)
            assertTrue(request.origin.hasHeader("Proxy-Authorization"))
        }
    }

    @Test
    fun httpsProxyAuthenticatesConnectWithoutLeakingIntoTlsOrigin() = runBlocking {
        TestTlsMaterial().use { material ->
            ProxyServer(material).use { proxy ->
                val config = config(proxy)
                val engine = CIO.create {
                    this.proxy = ProxyBuilder.http(config.proxy!!.url)
                    https { trustManager = material.trustManager }
                }
                KtorHttpClient(ProxyLogger, config = config, engine = engine).use { client ->
                    repeat(3) {
                        assertEquals("ok", client.get("https://localhost/translate").fold({ it }, { error(it.message) }))
                    }
                }
                assertEquals(3, proxy.requests.size)
                proxy.requests.forEach { request ->
                    assertTrue(request.connect!!.hasHeader("Proxy-Authorization"))
                    assertFalse(request.origin.hasHeader("Proxy-Authorization"))
                }
            }
        }
    }

    @Test
    fun redirectDoesNotCarryProxyCredentialsIntoEitherHttpsOrigin() = runBlocking {
        TestTlsMaterial().use { material ->
            ProxyServer(material) { head ->
                if ("/start" in head) Reply("302 Found", "", mapOf("Location" to "https://second.invalid/finish"))
                else Reply()
            }.use { proxy ->
                val config = config(proxy)
                val engine = CIO.create {
                    this.proxy = ProxyBuilder.http(config.proxy!!.url)
                    https { trustManager = material.trustManager }
                }
                KtorHttpClient(ProxyLogger, config = config, engine = engine).use { client ->
                    assertEquals("ok", client.get("https://first.invalid/start").fold({ it }, { error(it.message) }))
                }
                assertEquals(2, proxy.requests.size)
                assertTrue(proxy.requests[0].connect!!.startsWith("CONNECT first.invalid:443"))
                assertTrue(proxy.requests[1].connect!!.startsWith("CONNECT second.invalid:443"))
                proxy.requests.forEach {
                    assertTrue(it.connect!!.hasHeader("Proxy-Authorization"))
                    assertFalse(it.origin.hasHeader("Proxy-Authorization"))
                }
            }
        }
    }

    private fun config(proxy: ProxyServer) = HttpClientConfig(
        enableRetry = false, requestTimeoutMillis = 10_000,
        proxy = ProxyConfiguration(proxy.url, "test-user", "test-password")
    )
}

private data class CapturedProxyRequest(val connect: String?, val origin: String)
private data class Reply(val status: String = "200 OK", val body: String = "ok", val headers: Map<String, String> = emptyMap())

private class ProxyServer(
    private val tls: TestTlsMaterial? = null,
    private val reply: (String) -> Reply = { Reply() }
) : Closeable {
    private val listener = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    private val recorded = Collections.synchronizedList(mutableListOf<CapturedProxyRequest>())
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "Тест изоляции прокси").apply { isDaemon = true } }
    val requests: List<CapturedProxyRequest> get() = synchronized(recorded) { recorded.toList() }
    val url: String get() = "http://127.0.0.1:" + listener.localPort

    init {
        workers.submit {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets += socket
                workers.submit {
                    socket.use {
                        runCatching {
                            socket.soTimeout = 10_000
                            val head = readHead(socket.getInputStream())
                            if (tls == null) {
                                recorded += CapturedProxyRequest(null, head)
                                writeReply(socket, reply(head))
                            } else {
                                socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                                socket.getOutputStream().flush()
                                (tls.context.socketFactory.createSocket(socket, "localhost", 443, true) as SSLSocket).use { secure ->
                                    secure.useClientMode = false
                                    secure.soTimeout = 10_000
                                    secure.startHandshake()
                                    val origin = readHead(secure.getInputStream())
                                    recorded += CapturedProxyRequest(head, origin)
                                    writeReply(secure, reply(origin))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun writeReply(socket: Socket, response: Reply) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(response.status).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            response.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().write(body)
        socket.getOutputStream().flush()
    }

    override fun close() {
        listener.close()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        workers.shutdownNow()
    }
}

private class TestTlsMaterial : Closeable {
    private val directory = Files.createTempDirectory("camwork-proxy-tls").toFile()
    val context: SSLContext
    val trustManager: X509TrustManager

    init {
        val file = directory.resolve("test.p12")
        val password = "public-test-fixture"
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val keytool = File(System.getProperty("java.home"), "bin/keytool" + suffix)
        val process = ProcessBuilder(
            keytool.absolutePath, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost,dns:first.invalid,dns:second.invalid",
            "-keystore", file.absolutePath, "-storetype", "PKCS12",
            "-storepass", password, "-keypass", password, "-noprompt"
        ).redirectErrorStream(true).start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Не удалось создать тестовый TLS-сертификат за отведённое время")
        }
        check(process.exitValue() == 0) { "keytool не создал тестовый TLS-сертификат: " + process.inputStream.bufferedReader().readText() }
        val store = KeyStore.getInstance("PKCS12")
        file.inputStream().use { store.load(it, password.toCharArray()) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, password.toCharArray())
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(store)
        trustManager = trust.trustManagers.filterIsInstance<X509TrustManager>().single()
        context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
    }

    override fun close() { directory.deleteRecursively() }
}

private fun readHead(input: InputStream): String = buildString {
    val reader = input.bufferedReader(Charsets.US_ASCII)
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) break
        append(line).append('\n')
    }
}

private fun String.hasHeader(name: String): Boolean =
    lineSequence().any { it.substringBefore(':').equals(name, ignoreCase = true) }

private object ProxyLogger : Logger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, error: Throwable?) = Unit
}
