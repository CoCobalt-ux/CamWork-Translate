package com.github.ahatem.qtranslate.core.plugin.installer

import com.github.ahatem.qtranslate.core.plugin.InspectedPluginJar
import com.github.ahatem.qtranslate.core.plugin.LoadedPluginResult
import com.github.ahatem.qtranslate.core.plugin.PluginLoader
import com.github.ahatem.qtranslate.core.plugin.PluginStatus
import com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginLifecycleHandler
import com.github.ahatem.qtranslate.core.plugin.registry.PluginContainer
import com.github.ahatem.qtranslate.core.plugin.registry.PluginError
import com.github.ahatem.qtranslate.core.plugin.registry.PluginRegistry
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.plugin.storage.pluginSecretStoreId
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Файловые операции сериализованы отдельно от коротких операций реестра. */
internal class PluginInstaller(
    private val pluginsDir: File,
    private val appDataDirectory: File,
    private val registry: PluginRegistry,
    private val lifecycleHandler: PluginLifecycleHandler,
    private val pluginKeyValueStore: PluginKeyValueStore,
    private val settingsRepository: SettingsRepository,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("PluginInstaller")
    private val loader = PluginLoader(loggerFactory.getLogger("PluginLoader"))
    private val operationMutex = Mutex()

    suspend fun installPlugin(sourceJar: File): Result<Unit, String> = operationMutex.withLock {
        try {
            withContext(Dispatchers.IO) { installLocked(sourceJar) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Err("Не удалось завершить установку или очистку файлов: " + (error.message ?: error.javaClass.simpleName))
        }
    }

    private suspend fun installLocked(sourceJar: File): Result<Unit, String> {
        val inspected = loader.inspectPluginJar(sourceJar)
            ?: return Err("Выбранный файл не прошёл проверку плагина.")
        val id = inspected.manifest.id
        val duplicate = registry.mutex.withLock { registry.contains(id) } ||
            loader.inspectPluginsFromDirectory(pluginsDir).any { it.manifest.id == id }
        if (duplicate) return Err("Плагин с идентификатором '" + id + "' уже установлен.")
        pluginsDir.mkdirs()
        val destination = File(pluginsDir, id + ".jar")
        if (destination.exists()) return Err("Файл назначения уже существует; другой плагин не будет заменён.")
        val staged = Files.createTempFile(pluginsDir.toPath(), ".install-", ".tmp").toFile()
        var published = false
        var committed = false
        try {
            Files.copy(sourceJar.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val stagedInfo = loader.inspectPluginJar(staged)
                ?: return Err("Копия плагина не прошла проверку.")
            if (stagedInfo.jarHash != inspected.jarHash || stagedInfo.manifest != inspected.manifest) {
                return Err("Исходный JAR изменился во время копирования; установка отменена.")
            }
            // Без REPLACE_EXISTING: даже внешняя коллизия имени не должна уничтожать чужой файл.
            Files.move(staged.toPath(), destination.toPath())
            published = true
            val result = loader.loadInspectedPlugin(stagedInfo.copy(jarFile = destination))
                ?: return Err("Не удалось загрузить плагин; установка отменена.")
            initializeAndEnable(result)
            committed = true
            return Ok(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error("Установка плагина не завершена: " + error.javaClass.simpleName)
            return Err("Не удалось установить плагин: " + (error.message ?: error.javaClass.simpleName))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                Files.deleteIfExists(staged.toPath())
                if (published && !committed) Files.deleteIfExists(destination.toPath())
            }
        }
    }

    suspend fun uninstallPlugin(pluginId: String): Result<Unit, String> = operationMutex.withLock {
        withContext(Dispatchers.IO) { uninstallLocked(pluginId) }
    }

    private suspend fun uninstallLocked(pluginId: String): Result<Unit, String> {
        val container = registry.mutex.withLock { registry.get(pluginId) } ?: return Ok(Unit)
        lifecycleHandler.shutdown(container)
        try {
            Files.deleteIfExists(container.jarFile.toPath())
            purgePluginSandbox(pluginId)
            val disabled = settingsRepository.loadDisabledPluginIds()
            settingsRepository.saveDisabledPluginIds(disabled - pluginId)
            registry.mutex.withLock { registry.remove(pluginId) }
            return Ok(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = "Не удалось полностью удалить плагин: " + (error.message ?: error.javaClass.simpleName)
            registry.mutex.withLock {
                container.status = PluginStatus.FAILED
                container.lastError = PluginError.LoadFailure(pluginId, message, null, container.jarFile.absolutePath)
            }
            return Err(message)
        }
    }

    /** Подтверждение относится к конкретному hash, а не к любому JAR, появившемуся по тому же пути. */
    suspend fun activateVerifiedPlugin(inspected: InspectedPluginJar, cleanInstall: Boolean): Result<Unit, String> =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                if (registry.mutex.withLock { registry.contains(inspected.manifest.id) }) return@withContext Ok(Unit)
                val current = loader.inspectPluginJar(inspected.jarFile)
                    ?: return@withContext Err("Подтверждённый JAR больше недоступен.")
                if (current != inspected) return@withContext Err("JAR изменился; требуется повторная проверка.")
                try {
                    if (cleanInstall) purgePluginSandbox(inspected.manifest.id)
                    val loaded = loader.loadInspectedPlugin(inspected)
                        ?: return@withContext Err("Не удалось загрузить подтверждённый плагин.")
                    initializeAndEnable(loaded)
                    Ok(Unit)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Err("Не удалось включить плагин: " + (error.message ?: error.javaClass.simpleName))
                }
            }
        }

    suspend fun removeUnloadedPlugin(inspected: InspectedPluginJar): Result<Unit, String> = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (registry.mutex.withLock { registry.contains(inspected.manifest.id) }) {
                return@withContext uninstallLocked(inspected.manifest.id)
            }
            try {
                Files.deleteIfExists(inspected.jarFile.toPath())
                purgePluginSandbox(inspected.manifest.id)
                Ok(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Err("Не удалось удалить плагин: " + (error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private suspend fun initializeAndEnable(result: LoadedPluginResult) {
        var container: PluginContainer? = null
        try {
            container = PluginContainer(
                plugin = result.plugin, manifest = result.manifest,
                context = lifecycleHandler.createContext(result), jarFile = result.jarFile,
                jarHash = result.jarHash, classLoader = result.classLoader
            )
            if (!lifecycleHandler.initialize(container)) error(container.lastError?.message ?: "Ошибка инициализации.")
            container.declaredServices = container.plugin.getServices()
            if (container.id !in settingsRepository.loadDisabledPluginIds()) lifecycleHandler.enable(container)
            if (container.status == PluginStatus.FAILED) error(container.lastError?.message ?: "Ошибка включения.")
            registry.mutex.withLock { registry.put(container) }
        } catch (error: Throwable) {
            if (container != null) lifecycleHandler.shutdown(container)
            else (result.classLoader as? java.io.Closeable)?.close()
            throw error
        }
    }

    private suspend fun purgePluginSandbox(pluginId: String) {
        pluginKeyValueStore.deleteAllData(pluginId)
        pluginKeyValueStore.deleteAllData(pluginSecretStoreId(pluginId))
        val root = File(appDataDirectory, "plugins_data").canonicalFile
        val dataDir = File(root, pluginId).canonicalFile
        require(dataDir.toPath().startsWith(root.toPath()) && dataDir != root) { "Недопустимый путь данных плагина." }
        if (dataDir.exists() && !dataDir.deleteRecursively()) throw IOException("Не удалось удалить каталог данных плагина.")
    }
}
