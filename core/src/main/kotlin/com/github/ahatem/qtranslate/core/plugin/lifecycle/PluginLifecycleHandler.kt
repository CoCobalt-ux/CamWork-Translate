package com.github.ahatem.qtranslate.core.plugin.lifecycle

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.core.plugin.KtorHttpClientOf
import com.github.ahatem.qtranslate.core.plugin.LoadedPluginResult
import com.github.ahatem.qtranslate.core.plugin.PluginStatus
import com.github.ahatem.qtranslate.core.plugin.ScopedPluginContext
import com.github.ahatem.qtranslate.core.plugin.registry.PluginContainer
import com.github.ahatem.qtranslate.core.plugin.registry.PluginError
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceValidator
import com.github.ahatem.qtranslate.core.plugin.http.HttpClientConfig
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.plugin.text.PluginTextResolver
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File

/** Операции одного плагина последовательны; отказ не мешает повторному включению и очистке. */
internal class PluginLifecycleHandler(
    private val appDataDirectory: File,
    private val pluginKeyValueStore: PluginKeyValueStore,
    private val notificationBus: NotificationBus,
    private val textResolver: PluginTextResolver,
    private val loggerFactory: LoggerFactory,
    private val httpConfig: () -> HttpClientConfig = { HttpClientConfig() },
    private val httpFactory: (Logger, HttpClientConfig) -> HttpClient = ::KtorHttpClientOf,
    private val initTimeoutMillis: Long = 30_000,
    private val enableTimeoutMillis: Long = 15_000,
    private val disableTimeoutMillis: Long = 15_000
) {
    private val logger = loggerFactory.getLogger("PluginLifecycleHandler")

    fun createContext(result: LoadedPluginResult): ScopedPluginContext {
        textResolver.onPluginLoaded(result.manifest.id, result.classLoader)
        return ScopedPluginContext(
            pluginId = result.manifest.id,
            appDataDirectory = appDataDirectory,
            pluginKeyValueStore = pluginKeyValueStore,
            notificationBus = notificationBus,
            textResolver = textResolver,
            logger = loggerFactory.getLogger(result.manifest.id),
            httpConfig = httpConfig(),
            httpFactory = httpFactory
        )
    }

    fun forgetLocalization(pluginId: String) = textResolver.onPluginRemoved(pluginId)

    suspend fun initialize(container: PluginContainer): Boolean = container.lifecycleMutex.withLock {
        try {
            initializeLocked(container)
        } catch (cancelled: CancellationException) {
            (container.context as? ScopedPluginContext)?.cancelScope()
            throw cancelled
        }
    }

    private suspend fun initializeLocked(container: PluginContainer): Boolean {
        if (container.disposed) return false
        (container.context as? ScopedPluginContext)?.resetScope()
        val outcome = bounded(initTimeoutMillis) { container.plugin.initialize(container.context) }
        val error = outcome?.fold(
            onSuccess = { it.fold(success = { null }, failure = { failure -> failure.message }) },
            onFailure = { "Ошибка инициализации: " + it.javaClass.simpleName }
        ) ?: if (outcome == null) "Истекло время инициализации плагина." else null
        container.initialized = error == null
        if (error != null) {
            container.status = PluginStatus.FAILED
            container.lastError = PluginError.InitializationFailure(container.id, error, null)
            (container.context as? ScopedPluginContext)?.cancelScope()
            logger.error("Не удалось инициализировать плагин " + container.id + ": " + error)
        }
        return container.initialized
    }

    suspend fun enable(container: PluginContainer) = container.lifecycleMutex.withLock {
        if (container.disposed || container.status == PluginStatus.ENABLED) return@withLock
        if (!container.initialized && !initializeLocked(container)) return@withLock
        (container.context as? ScopedPluginContext)?.resetScope()
        try {
            val outcome = bounded(enableTimeoutMillis) {
                container.plugin.onEnable().fold(
                    success = { container.plugin.getServices() },
                    failure = { throw IllegalStateException(it.message, it.cause) }
                )
            }
            if (outcome == null || outcome.isFailure) {
                val reason = if (outcome == null) "Истекло время включения плагина."
                    else "Ошибка включения: " + outcome.exceptionOrNull()!!.javaClass.simpleName
                container.status = PluginStatus.FAILED
                container.lastError = PluginError.EnableFailure(container.id, reason, null)
                container.services = emptyList()
                (container.context as? ScopedPluginContext)?.cancelScope()
                logger.error("Не удалось включить плагин " + container.id + ": " + reason)
                return@withLock
            }
            val (valid, invalid) = outcome.getOrThrow().partition { ServiceValidator.validate(it).isEmpty() }
            container.services = valid
            container.declaredServices = valid
            container.status = PluginStatus.ENABLED
            container.lastError = if (invalid.isEmpty()) null else
                PluginError.EnableFailure(container.id, "Часть сервисов не прошла проверку.", null)
        } catch (cancelled: CancellationException) {
            container.services = emptyList()
            container.status = PluginStatus.DISABLED
            (container.context as? ScopedPluginContext)?.cancelScope()
            throw cancelled
        }
    }

    suspend fun disable(container: PluginContainer) = container.lifecycleMutex.withLock {
        if (container.disposed) return@withLock
        try {
            bounded(disableTimeoutMillis) { container.plugin.onDisable() }
        } finally {
            (container.context as? ScopedPluginContext)?.cancelScope()
            container.services = emptyList()
            container.status = PluginStatus.DISABLED
            container.lastError = null
        }
    }

    /** Даже исключение shutdown или отмена вызывающей корутины не оставляет JAR/HTTP открытыми. */
    suspend fun shutdown(container: PluginContainer) = withContext(NonCancellable) {
        container.lifecycleMutex.withLock {
            if (container.disposed) return@withLock
            try {
                if (container.status == PluginStatus.ENABLED) {
                    bounded(disableTimeoutMillis) { container.plugin.onDisable() }
                }
                bounded(disableTimeoutMillis) { container.plugin.shutdown() }
            } finally {
                container.disposed = true
                container.initialized = false
                container.services = emptyList()
                container.status = PluginStatus.DISABLED
                (container.context as? ScopedPluginContext)?.let { context ->
                    context.cancelScope()
                    runCatching { context.closeHttp() }.onFailure {
                        logger.error("Не удалось закрыть HTTP-клиент плагина " + container.id)
                    }
                }
                runCatching { (container.classLoader as? Closeable)?.close() }.onFailure {
                    logger.error("Не удалось закрыть загрузчик плагина " + container.id)
                }
                forgetLocalization(container.id)
            }
        }
    }

    private suspend fun <T> bounded(timeoutMillis: Long, action: suspend () -> T): kotlin.Result<T>? =
        withTimeoutOrNull(timeoutMillis) {
            try {
                kotlin.Result.success(action())
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                kotlin.Result.failure(cancelled)
            } catch (error: Exception) {
                kotlin.Result.failure(error)
            }
        }
}
