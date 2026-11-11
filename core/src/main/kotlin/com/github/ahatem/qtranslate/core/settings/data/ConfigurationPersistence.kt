package com.github.ahatem.qtranslate.core.settings.data

import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.Flow

/** Хранилище конфигурации: позволяет проверять отказ записи без повреждения реальных настроек. */
interface ConfigurationPersistence {
    /** Новая подписка начинает с чтения текущей сохранённой конфигурации. */
    val configuration: Flow<Configuration>

    suspend fun updateConfiguration(config: Configuration): Result<Unit, SettingsError>
}
