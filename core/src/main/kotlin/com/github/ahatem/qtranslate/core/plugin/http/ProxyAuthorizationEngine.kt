package com.github.ahatem.qtranslate.core.plugin.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.InternalAPI

/**
 * Локальный адаптер CIO 3.5.2: startTunnel читает Proxy-Authorization по имени,
 * а writeHeaders перечисляет entries. Учётные данные CONNECT не входят в сериализуемые
 * заголовки origin. Такой view создаётся только после общего HTTP-pipeline, у границы движка.
 * При обновлении Ktor обязательны сквозные HTTP/CONNECT/TLS/redirect регрессии.
 */
@OptIn(InternalAPI::class)
internal class ProxyAuthorizationEngine(
    private val delegate: HttpClientEngine,
    private val authorization: String?
) : HttpClientEngine by delegate {
    override fun install(client: HttpClient) = super<HttpClientEngine>.install(client)

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val visible = Headers.build {
            data.headers.forEach { name, values ->
                if (!name.equals(HttpHeaders.ProxyAuthorization, ignoreCase = true)) appendAll(name, values)
            }
        }
        val headers = when {
            authorization == null -> visible
            data.url.protocol.name.lowercase() in setOf("https", "wss") -> TunnelOnlyProxyHeaders(visible, authorization)
            else -> Headers.build {
                appendAll(visible)
                append(HttpHeaders.ProxyAuthorization, authorization)
            }
        }
        return delegate.execute(HttpRequestData(data.url, data.method, headers, data.body, data.executionContext, data.attributes))
    }
}

private class TunnelOnlyProxyHeaders(
    private val visible: Headers,
    private val authorization: String
) : Headers by visible {
    override fun get(name: String): String? =
        if (name.equals(HttpHeaders.ProxyAuthorization, ignoreCase = true)) authorization else visible[name]

    override fun getAll(name: String): List<String>? =
        if (name.equals(HttpHeaders.ProxyAuthorization, ignoreCase = true)) listOf(authorization) else visible.getAll(name)
}
