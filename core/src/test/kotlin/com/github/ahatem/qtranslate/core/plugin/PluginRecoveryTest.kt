package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.plugin.http.HttpClientConfig
import com.github.ahatem.qtranslate.core.plugin.installer.PluginInstaller
import com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginLifecycleHandler
import com.github.ahatem.qtranslate.core.plugin.registry.PluginContainer
import com.github.ahatem.qtranslate.core.plugin.registry.PluginRegistry
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.plugin.storage.ScopedSecretStore
import com.github.ahatem.qtranslate.core.plugin.text.PluginTextResolver
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginRecoveryTest {
    @BeforeTest
    fun resetFixture() {
        AuditFixtureState.constructions.set(0)
        AuditFixtureState.shutdowns.set(0)
        AuditFixtureState.failInitialize = false
        AuditFixtureState.failServices = false
        AuditFixtureState.failShutdown = false
    }

    @Test
    fun incompatibleJarDoesNotExecuteConstructorAndValidJarStillLoads() {
        val root = Files.createTempDirectory("camwork-loader-recovery").toFile()
        try {
            val loader = PluginLoader(SilentLogger)
            val file = pluginJar(root.resolve("plugin.jar"), api = "99.0.0")
            assertNull(loader.loadPluginFromFile(file))
            assertEquals(0, AuditFixtureState.constructions.get())
            pluginJar(file)
            val inspected = assertNotNull(loader.inspectPluginJar(file))
            assertEquals(0, AuditFixtureState.constructions.get())
            val loaded = assertNotNull(loader.loadInspectedPlugin(inspected))
            assertEquals(1, AuditFixtureState.constructions.get())
            (loaded.classLoader as Closeable).close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verificationBlocksConstructorUntilExplicitApproval() = runBlocking {
        val fixture = Fixture()
        try {
            pluginJar(fixture.root.resolve("plugins/fixture.jar"))
            val manager = fixture.manager(mapOf("audit-plugin" to "untrusted"))
            manager.loadAndProcessPlugins()
            assertEquals(0, AuditFixtureState.constructions.get())
            assertEquals(PluginStatus.AWAITING_VERIFICATION, manager.plugins.value.single().status)
            val decisions = coroutineScope { List(12) { async { manager.resolveAsUpdate("audit-plugin") } }.awaitAll() }
            assertTrue(decisions.all { it.isOk })
            assertEquals(1, AuditFixtureState.constructions.get())
            assertEquals(PluginStatus.ENABLED, manager.plugins.value.single().status)
            manager.shutdown()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun concurrentApprovalAndUninstallCannotLeaveAnActiveOrLeakedPlugin() = runBlocking {
        val fixture = Fixture()
        try {
            pluginJar(fixture.root.resolve("plugins/fixture.jar"))
            val manager = fixture.manager(mapOf("audit-plugin" to "untrusted"))
            manager.loadAndProcessPlugins()
            coroutineScope {
                val approve = async { manager.resolveAsUpdate("audit-plugin") }
                val remove = async { manager.uninstallPlugin("audit-plugin") }
                approve.await()
                assertTrue(remove.await().isOk)
            }
            assertTrue(manager.plugins.value.isEmpty())
            assertTrue(fixture.clients.all { it.closes.get() == 1 })
            manager.shutdown()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun modifiedJarCannotReuseAnEarlierApproval() = runBlocking {
        val fixture = Fixture()
        try {
            val jar = pluginJar(fixture.root.resolve("plugins/fixture.jar"))
            val manager = fixture.manager(mapOf("audit-plugin" to "untrusted"))
            manager.loadAndProcessPlugins()
            pluginJar(jar, version = "2.0.0")
            assertTrue(manager.resolveAsUpdate("audit-plugin").isErr)
            assertEquals(0, AuditFixtureState.constructions.get())
            assertEquals(PluginStatus.AWAITING_VERIFICATION, manager.plugins.value.single().status)
            assertTrue(manager.uninstallPlugin("audit-plugin").isOk)
            assertFalse(jar.exists())
            manager.shutdown()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun equalSourceFileNamesDoNotOverwriteAnotherPlugin() = runBlocking {
        val fixture = Fixture()
        try {
            val first = pluginJar(fixture.root.resolve("source-a/plugin.jar"), id = "audit-first")
            val second = pluginJar(fixture.root.resolve("source-b/plugin.jar"), id = "audit-second")
            val manager = fixture.manager()
            assertTrue(manager.installPlugin(first).isOk)
            val published = fixture.root.resolve("plugins/audit-first.jar")
            val original = published.readBytes()
            assertTrue(manager.installPlugin(second).isOk)
            assertTrue(original.contentEquals(published.readBytes()))
            assertTrue(fixture.root.resolve("plugins/audit-second.jar").isFile)
            assertEquals(2, manager.plugins.value.size)
            manager.shutdown()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedInstallRollsBackJarAndNextInstallSucceeds() = runBlocking {
        val fixture = Fixture()
        try {
            val source = pluginJar(fixture.root.resolve("source/plugin.jar"))
            val manager = fixture.manager()
            AuditFixtureState.failInitialize = true
            assertTrue(manager.installPlugin(source).isErr)
            assertTrue(manager.plugins.value.isEmpty())
            assertTrue(fixture.root.resolve("plugins").listFiles().orEmpty().none { it.extension == "jar" })
            assertEquals(1, fixture.clients.single().closes.get())
            AuditFixtureState.failInitialize = false
            assertTrue(manager.installPlugin(source).isOk)
            assertEquals(PluginStatus.ENABLED, manager.plugins.value.single().status)
            manager.shutdown()
            assertTrue(fixture.clients.all { it.closes.get() == 1 })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun concurrentInstallThenUninstallPurgesSecretsAndAllowsReinstall() = runBlocking {
        val fixture = Fixture()
        try {
            val source = pluginJar(fixture.root.resolve("source/plugin.jar"))
            val manager = fixture.manager()
            val results = coroutineScope { List(20) { async { manager.installPlugin(source) } }.awaitAll() }
            assertEquals(1, results.count { it.isOk })
            val secrets = ScopedSecretStore("audit-plugin", "default", fixture.store)
            secrets.put("apiKey", "test-marker")
            fixture.store.storeValue("audit-plugin", "setting", "test-marker")
            AuditFixtureState.failShutdown = true
            assertTrue(manager.uninstallPlugin("audit-plugin").isOk)
            assertTrue(manager.plugins.value.isEmpty())
            assertNull(secrets.get("apiKey"))
            assertNull(fixture.store.getValue("audit-plugin", "setting"))
            assertFalse(fixture.root.resolve("plugins/audit-plugin.jar").exists())
            assertEquals(1, fixture.clients.single().closes.get())
            AuditFixtureState.failShutdown = false
            assertTrue(manager.installPlugin(source).isOk)
            assertEquals(PluginStatus.ENABLED, manager.plugins.value.single().status)
            manager.shutdown()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun clearAndParallelWritesReuseOneDataStore() = runBlocking {
        val fixture = Fixture()
        try {
            repeat(10) { generation ->
                fixture.store.deleteAllData("audit-plugin")
                coroutineScope {
                    List(30) { index ->
                        async { fixture.store.storeValue("audit-plugin", "key-" + index, generation.toString()) }
                    }.awaitAll()
                }
                assertEquals(generation.toString(), fixture.store.getValue("audit-plugin", "key-29"))
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun deletionFailureIsReportedAndCanBeRetried() = runBlocking {
        val fixture = Fixture()
        try {
            val registry = PluginRegistry()
            val path = fixture.root.resolve("not-empty.jar").apply { mkdirs() }
            val child = path.resolve("busy.txt").apply { writeText("test") }
            val loader = URLClassLoader(emptyArray(), javaClass.classLoader)
            val loaded = LoadedPluginResult(AuditFixturePlugin(), manifest(), path, "test", loader)
            val handler = fixture.lifecycle()
            val container = PluginContainer(loaded.plugin, loaded.manifest, handler.createContext(loaded), path, "test", loader)
            assertTrue(handler.initialize(container))
            handler.enable(container)
            registry.mutex.lock()
            try { registry.put(container) } finally { registry.mutex.unlock() }
            val installer = PluginInstaller(fixture.root.resolve("plugins"), fixture.root, registry, handler, fixture.store, fixture.settings, Loggers)
            assertTrue(installer.uninstallPlugin("audit-plugin").isErr)
            assertEquals(PluginStatus.FAILED, container.status)
            assertEquals(1, fixture.clients.single().closes.get())
            assertTrue(child.delete())
            assertTrue(installer.uninstallPlugin("audit-plugin").isOk)
            assertFalse(path.exists())
            assertEquals(1, AuditFixtureState.shutdowns.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serviceEnumerationFailureAllowsRetryWithoutRestart() = runBlocking {
        val fixture = Fixture()
        try {
            val loader = URLClassLoader(emptyArray(), javaClass.classLoader)
            val loaded = LoadedPluginResult(AuditFixturePlugin(), manifest(), fixture.root.resolve("fixture.jar"), "test", loader)
            val handler = fixture.lifecycle()
            val container = PluginContainer(loaded.plugin, loaded.manifest, handler.createContext(loaded), loaded.jarFile, "test", loader)
            assertTrue(handler.initialize(container))
            AuditFixtureState.failServices = true
            handler.enable(container)
            assertEquals(PluginStatus.FAILED, container.status)
            AuditFixtureState.failServices = false
            handler.enable(container)
            assertEquals(PluginStatus.ENABLED, container.status)
            handler.shutdown(container)
            assertEquals(1, fixture.clients.single().closes.get())
        } finally {
            fixture.close()
        }
    }

    private class Fixture : Closeable {
        val root: File = Files.createTempDirectory("camwork-plugin-recovery").toFile()
        val store = PluginKeyValueStore(root)
        val settings = SettingsRepository(root, Json, SilentLogger)
        val clients: MutableList<ClosingClient> = Collections.synchronizedList(mutableListOf())
        val httpFactory: (Logger, HttpClientConfig) -> HttpClient = { _, _ -> ClosingClient().also { clients += it } }

        fun lifecycle() = PluginLifecycleHandler(root, store, NotificationBus(), PluginTextResolver.Fallback, Loggers, httpFactory = httpFactory)
        fun manager(trusted: Map<String, String> = emptyMap()) = PluginManager(
            root, settings, PluginFingerprintRepository(root, Json), store, Loggers,
            NotificationBus(), trusted, pluginHttpFactory = httpFactory
        )

        override fun close() {
            store.close()
            root.deleteRecursively()
        }
    }

    private companion object {
        fun manifest(id: String = "audit-plugin", api: String = "2.1.0", version: String = "1.0.0") =
            PluginManifest(id, "Тестовый плагин", version, "Тест", "Изолированная проверка", api)

        fun pluginJar(file: File, id: String = "audit-plugin", api: String = "2.1.0", version: String = "1.0.0"): File {
            file.parentFile.mkdirs()
            JarOutputStream(file.outputStream()).use { archive ->
                fun entry(name: String, bytes: ByteArray) {
                    archive.putNextEntry(JarEntry(name))
                    archive.write(bytes)
                    archive.closeEntry()
                }
                entry("plugin.json", Json.encodeToString(manifest(id, api, version)).toByteArray())
                entry("META-INF/services/com.github.ahatem.qtranslate.api.plugin.Plugin", AuditFixturePlugin::class.java.name.toByteArray())
                val classFile = AuditFixturePlugin::class.java.name.replace('.', '/') + ".class"
                entry(classFile, AuditFixturePlugin::class.java.classLoader.getResourceAsStream(classFile)!!.use { it.readBytes() })
            }
            return file
        }
    }
}

object AuditFixtureState {
    val constructions = AtomicInteger()
    val shutdowns = AtomicInteger()
    @Volatile var failInitialize = false
    @Volatile var failServices = false
    @Volatile var failShutdown = false
}

class AuditFixturePlugin : Plugin<PluginSettings.None> {
    init { AuditFixtureState.constructions.incrementAndGet() }
    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> =
        if (AuditFixtureState.failInitialize) Err(ServiceError.ConfigurationError("Тестовый отказ")) else Ok(Unit)
    override fun getServices(): List<Service> {
        if (AuditFixtureState.failServices) error("Тестовый сбой перечисления сервисов")
        return emptyList()
    }
    override fun getSettings(): PluginSettings.None = PluginSettings.None
    override suspend fun shutdown() {
        AuditFixtureState.shutdowns.incrementAndGet()
        if (AuditFixtureState.failShutdown) error("Тестовый сбой завершения")
    }
}

private object SilentLogger : Logger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, error: Throwable?) = Unit
}

private object Loggers : LoggerFactory {
    override fun getLogger(name: String): Logger = SilentLogger
}

private class ClosingClient : HttpClient, Closeable {
    val closes = AtomicInteger()
    override fun close() { closes.incrementAndGet() }
    override suspend fun get(url: String, headers: Map<String, String>, queryParams: Map<String, Any?>): Result<String, ServiceError> = unexpected()
    override suspend fun post(url: String, headers: Map<String, String>, body: String?, queryParams: Map<String, Any?>): Result<String, ServiceError> = unexpected()
    override suspend fun postForm(url: String, formData: Map<String, String>, headers: Map<String, String>, queryParams: Map<String, Any?>, cookies: Map<String, String>): Result<String, ServiceError> = unexpected()
    override suspend fun postFormBytes(url: String, formData: Map<String, String>, headers: Map<String, String>, queryParams: Map<String, Any?>, cookies: Map<String, String>): Result<ByteArray, ServiceError> = unexpected()
    override suspend fun getBytes(url: String, headers: Map<String, String>, queryParams: Map<String, Any?>): Result<ByteArray, ServiceError> = unexpected()
    private fun unexpected(): Nothing = error("Этот сценарий не должен обращаться к сети")
}
