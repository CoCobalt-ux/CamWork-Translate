package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.spell.WindowsSpellCheckerBackend
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold

class SystemServicesPlugin : Plugin<PluginSettings.None> {
    private lateinit var context: PluginContext
    private var checker: SystemSpellCheckerService? = null

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        onDisable()
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return Ok(Unit)
        val backend = WindowsSpellCheckerBackend()
        try {
            backend.languages().fold(
                success = { languages ->
                    val service = SystemSpellCheckerService(backend, languages, context.logger)
                    if (service.supportedLanguages.languages.isNotEmpty()) checker = service
                },
                failure = { error ->
                    context.logger.info("Системная орфография недоступна: тип=${error::class.simpleName}")
                }
            )
        } finally {
            if (checker == null) backend.close()
        }
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        val previous = checker
        checker = null
        previous?.close()
    }

    override suspend fun shutdown() = onDisable()

    override fun getServices(): List<Service> = listOfNotNull(checker)

    override fun getSettings(): PluginSettings.None = PluginSettings.None
}
