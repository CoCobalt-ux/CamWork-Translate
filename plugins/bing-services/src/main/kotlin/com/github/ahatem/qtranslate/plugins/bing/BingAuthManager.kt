package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.getOrElse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.Boolean
import kotlin.Long
import kotlin.String
import kotlin.fold
import kotlin.let
import kotlin.require
import kotlin.runCatching
import kotlin.time.Duration.Companion.hours

/**
 * Thread-safe manager for Bing authentication tokens.
 * Tokens are automatically refreshed when expired (1 hour lifetime).
 */
class BingAuthManager(
    private val pluginContext: PluginContext,
    private val httpClient: HttpClient,
    private val apiConfig: ApiConfig = ApiConfig(),
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    private val authRef = AtomicReference<AuthState?>(null)
    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionUserAgent = apiConfig.defaultUserAgents.firstOrNull() ?: FALLBACK_USER_AGENT

    private companion object {
        const val TRANSLATOR_URL = "https://www.bing.com/translator"
        const val BING_HOME_URL = "https://www.bing.com/"
        const val FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    suspend fun getAuth(): Result<BingAuth, ServiceError> {
        val current = authRef.get()?.takeUnless { it.isExpired(clockMillis()) }
        if (current != null) return Ok(current.auth)

        return refreshMutex.withLock {
            authRef.get()
                ?.takeUnless { it.isExpired(clockMillis()) }
                ?.let { Ok(it.auth) }
                ?: refreshAuth()
        }
    }

    /** Удаляет только ту сессию, на которую пришёл auth-отказ. */
    fun invalidate(auth: BingAuth) {
        while (true) {
            val current = authRef.get() ?: return
            if (current.auth !== auth) return
            if (authRef.compareAndSet(current, null)) return
        }
    }

    private suspend fun refreshAuth(): Result<BingAuth, ServiceError> = coroutineBinding {
        pluginContext.logger.info("Fetching new Bing authentication token")

        val html = httpClient.get(
            url = TRANSLATOR_URL,
            headers = apiConfig.createHeaders(
                additionalHeaders = mapOf(
                    "User-Agent" to sessionUserAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to BING_HOME_URL
                ),
                randomizeUserAgent = false
            )
        ).bind()

        val auth = parseAuthFromHtml(html).bind()
        authRef.set(AuthState(auth, clockMillis()))

        pluginContext.logger.info("Successfully obtained Bing authentication token")
        auth
    }

    private fun parseAuthFromHtml(html: String): Result<BingAuth, ServiceError> {
        val ig = extractPattern(html, """IG:"(.*?)"""", "IG").getOrElse { return Err(it) }
        val iid = extractPattern(html, """data-iid="(.*?)"""", "IID").getOrElse { return Err(it) }
        val helperInfo = extractPattern(html, """params_AbusePreventionHelper = (.*?);""", "helper info")
            .getOrElse { return Err(it) }

        // Extract additional authentication data
        val muid = extractPattern(html, """muid":\s*"(.*?)"""", "MUID").getOr("")
        val sid = extractPattern(html, """sid":\s*"(.*?)"""", "SID").getOr("")
        val tid = extractPattern(html, """tid":\s*"(.*?)"""", "TID").getOr("")

        return runCatching {
            val jsonElement = json.parseToJsonElement(helperInfo)
            val helperArray = jsonElement.jsonArray
            require(helperArray.size >= 2) { "Invalid helper info format" }

            BingAuth(
                ig = ig,
                iid = iid,
                key = helperArray[0].toString(),
                token = helperArray[1].toString().removeSurrounding("\""),
                muid = muid,
                sid = sid,
                tid = tid,
                userAgent = sessionUserAgent
            )
        }.fold(
            onSuccess = { Ok(it) },
            onFailure = { Err(ServiceError.InvalidResponseError("Failed to parse auth data: ${it.message}", it)) }
        )
    }

    private fun extractPattern(html: String, pattern: String, fieldName: String): Result<String, ServiceError> =
        Regex(pattern).find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { Ok(it) }
            ?: Err(ServiceError.InvalidResponseError("Failed to extract $fieldName from Bing page", null))

    private data class AuthState(val auth: BingAuth, val timestamp: Long) {
        fun isExpired(nowMillis: Long): Boolean = (nowMillis - timestamp) >= 1.hours.inWholeMilliseconds
    }
}
