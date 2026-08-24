package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginDefaultsTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tempDir: File = Files.createTempDirectory("camwork-plugin-defaults").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `новая установка включает только основные плагины`() = runBlocking {
        val repository = repository("fresh")

        repository.loadInitialConfiguration()
        val disabled = repository.loadDisabledPluginIds()

        assertEquals(FRESH_INSTALL_DISABLED_PLUGIN_IDS, disabled)
        assertEquals(disabled, repository.loadDisabledPluginIds(), "дефолт должен быть сохранён")
    }

    @Test
    fun `существующая конфигурация не получает новые отключения`() = runBlocking {
        val repository = repository("existing")
        repository.updateConfiguration(Configuration.DEFAULT.copy(uiScale = 125))

        assertEquals(emptySet(), repository.loadDisabledPluginIds())
    }

    @Test
    fun `явный выбор пользователя имеет приоритет над дефолтом`() = runBlocking {
        val repository = repository("chosen")
        val chosen = setOf("google-services", "custom-plugin")
        repository.saveDisabledPluginIds(chosen)

        assertEquals(chosen, repository.loadDisabledPluginIds())
    }

    private fun repository(name: String): SettingsRepository {
        val directory = File(tempDir, name).apply { mkdirs() }
        return SettingsRepository(directory, json, logger)
    }
}
