package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationRequestContext
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.plugin.PluginStatus
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Быстрая headless-проверка уже упакованного приложения перед публикацией. */
internal suspend fun runPackagedSmokeTest() {
    System.setProperty("java.awt.headless", "true")
    val appData = AppDataDirectory.resolve()
    System.setProperty("logDir", File(appData, "logs").absolutePath)
    val loggerFactory = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.INFO)
    val settingsRepository = SettingsRepository(
        appDataDirectory = appData,
        json = Json { ignoreUnknownKeys = true; isLenient = true },
        logger = loggerFactory.getLogger("PackagedSmokeSettings")
    )
    val configuration = settingsRepository.loadInitialConfiguration().copy(
        autoCheckForUpdates = false,
        isHistoryEnabled = false
    )
    val dependencies = buildDependencies(appData, loggerFactory, settingsRepository, configuration)

    try {
        dependencies.pluginManager.loadAndProcessPlugins()
        val plugins = dependencies.pluginManager.plugins.value
        check(plugins.isNotEmpty()) { "Упакованное приложение не обнаружило плагины" }

        val trustManifest = BundledPluginTrust.load()
        check(trustManifest.isNotEmpty()) { "В host JAR отсутствует allowlist штатных плагинов" }
        val discoveredIds = plugins.mapTo(linkedSetOf()) { it.id }
        check(discoveredIds == trustManifest.keys) {
            "Набор plugin JAR не совпадает с host allowlist: discovered=$discoveredIds trusted=${trustManifest.keys}"
        }

        plugins.forEach { plugin ->
            val actualHash = sha256(File(plugin.jarPath))
            check(trustManifest[plugin.id].equals(actualHash, ignoreCase = true)) {
                "SHA-256 штатного плагина ${plugin.id} не совпадает с host allowlist"
            }
        }

        val statuses = plugins.associate { it.id to it.status }
        val unavailableRequired = MANAGED_TRANSLATION_PLUGIN_IDS.filter { statuses[it] != PluginStatus.ENABLED }
        check(unavailableRequired.isEmpty()) {
            "Основная цепочка перевода не активна: $unavailableRequired"
        }
        check(plugins.none { it.status == PluginStatus.FAILED || it.status == PluginStatus.AWAITING_VERIFICATION }) {
            "Есть повреждённые или неподтверждённые плагины"
        }

        val translation = runHostTranslationProbe(dependencies)

        val successMarker =
            "CAMWORK_PACKAGED_SMOKE_OK version=${com.github.ahatem.qtranslate.core.shared.AppConstants.APP_VERSION} " +
                "plugins=${plugins.size} primary=${MANAGED_TRANSLATION_PLUGIN_IDS.sorted().joinToString(",")} " +
                "translatedBy=${translation.translatorId}"
        File(appData, "logs/packaged-smoke-ok.txt").apply {
            parentFile.mkdirs()
            writeText(successMarker + "\n", Charsets.UTF_8)
        }
        println(successMarker)
    } finally {
        runCatching { dependencies.pluginManager.shutdown() }
        runCatching { dependencies.mainStore.onShutdown() }
        dependencies.appScope.cancel()
    }
}

/** Выполняет тот же host pipeline, которым пользуется главное окно, включая managed fallback. */
private suspend fun runHostTranslationProbe(
    dependencies: AppDependencies
): com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationRunResult.Success {
    var state = MainState(
        inputText = TRANSLATION_SMOKE_SOURCE_TEXT,
        sourceLanguage = LanguageCode.ENGLISH,
        targetLanguage = LanguageCode.RUSSIAN
    )
    val result = dependencies.translateTextUseCase(
        getState = { state },
        updateState = { transform -> state = state.transform() },
        onStatusUpdate = { _, _, _ -> },
        textOverride = TRANSLATION_SMOKE_SOURCE_TEXT,
        includeExtraOutput = false,
        applyTranslationRules = false,
        requestContext = TranslationRequestContext(
            requestId = 1L,
            origin = "packaged_smoke"
        )
    )
    return requireUsableManagedTranslation(result)
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
