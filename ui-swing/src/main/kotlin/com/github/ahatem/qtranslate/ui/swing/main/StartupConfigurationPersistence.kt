package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ConfigurationPersistence
import com.github.ahatem.qtranslate.core.settings.data.SettingsError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class StartupReconciliation(val configuration: Configuration, val warning: String?)

/** Проверка Windows входит в ту же операцию сохранения, которую ожидают SettingsStore и диалог. */
class StartupConfigurationPersistence internal constructor(
    private val delegate: ConfigurationPersistence,
    private var initialConfiguration: Configuration,
    private val startup: StartupRegistration,
    private val timeoutMillis: Long = 5_000
) : ConfigurationPersistence {
    constructor(delegate: ConfigurationPersistence, initialConfiguration: Configuration) :
        this(delegate, initialConfiguration, SystemStartupRegistration)

    private val mutex = Mutex()
    private val reconciledStartup = MutableStateFlow<Boolean?>(null)

    init { require(timeoutMillis > 0) }

    override val configuration: Flow<Configuration> = delegate.configuration.combine(reconciledStartup) { saved, actual ->
        if (actual == null) saved else saved.copy(launchOnSystemStartup = actual)
    }

    /** Вызывается только после успешного чтения конфигурации; smoke и recovery этот шаг пропускают. */
    suspend fun reconcileAtLaunch(): StartupReconciliation = withContext(Dispatchers.IO) {
        mutex.withLock {
            val saved = initialConfiguration
            var inspection = startup.inspect()
            var repairFailure: StartupFailure? = null
            val previousLauncher = saved.launchOnSystemStartupLauncherPath
            if (saved.launchOnSystemStartup && inspection.registeredElsewhere && previousLauncher != null) {
                startup.change(enabled = true, confirmedPreviousLauncher = previousLauncher).fold(
                    success = { if (it.changed) inspection = startup.inspect() },
                    failure = { repairFailure = it }
                )
            }
            // Удалённая запись Run может означать решение пользователя. Восстанавливаем
            // только существующую запись CamWork с путём к отсутствующему файлу.
            val failure = repairFailure ?: inspection.failure ?: StartupFailure.MISSING_ENTRY
            val resolved = saved.copy(
                launchOnSystemStartup = inspection.enabled,
                launchOnSystemStartupLauncherPath = inspection.launcherPath
            )
            var warning = if (saved.launchOnSystemStartup && !inspection.enabled) failure?.message else null
            if (resolved != saved) {
                // Это синхронизация фактического состояния, а не просьба удалить отключённую Run-запись.
                val persisted = try {
                    persistBounded(resolved)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Err(SettingsError.IOError("Не удалось сохранить фактическое состояние автозапуска."))
                }
                persisted.fold(
                    success = { reconciledStartup.value = null },
                    failure = {
                        reconciledStartup.value = inspection.enabled
                        warning = listOfNotNull(warning, it.message).joinToString("\n")
                    }
                )
            }
            initialConfiguration = resolved
            StartupReconciliation(resolved, warning)
        }
    }

    override suspend fun updateConfiguration(config: Configuration): Result<Unit, SettingsError> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                // При пропущенном reconciliation поздняя настоящая конфигурация не подменяется DEFAULT.
                val previous = reconciledStartup.value ?: try {
                    withTimeoutOrNull(timeoutMillis) { delegate.configuration.first().launchOnSystemStartup }
                        ?: return@withLock Err(SettingsError.IOError("Истекло время чтения настроек автозапуска. Повторите сохранение."))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withLock Err(SettingsError.IOError("Не удалось прочитать настройки автозапуска."))
                }
                val requiresChange = previous != config.launchOnSystemStartup || config.launchOnSystemStartup
                val mutation = if (requiresChange) {
                    startup.change(config.launchOnSystemStartup).fold(
                        success = { it },
                        failure = { return@withLock Err(SettingsError.IOError(it.message)) }
                    )
                } else StartupMutation(null, null, changed = false)

                // Закрытие диалога не должно разорвать запись конфигурации после изменения Windows.
                withContext(NonCancellable) {
                    try {
                        val verified = config.copy(launchOnSystemStartupLauncherPath = mutation.launcherPath)
                        val persisted = persistBounded(verified)
                        persisted.fold(
                            success = {
                                initialConfiguration = verified
                                reconciledStartup.value = null
                                Ok(Unit)
                            },
                            failure = { error -> failureAfterRollback(error, mutation) }
                        )
                    } catch (cancelled: CancellationException) {
                        startup.rollback(mutation)
                        throw cancelled
                    } catch (_: Exception) {
                        failureAfterRollback(SettingsError.IOError("Не удалось сохранить настройки."), mutation)
                    }
                }
            }
        }

    private fun failureAfterRollback(error: SettingsError, mutation: StartupMutation): Result<Unit, SettingsError> {
        val rollbackFailure = startup.rollback(mutation)
        return Err(if (rollbackFailure == null) error else SettingsError.IOError("${error.message}\n${rollbackFailure.message}"))
    }

    private suspend fun persistBounded(config: Configuration): Result<Unit, SettingsError> =
        withTimeoutOrNull(timeoutMillis) { delegate.updateConfiguration(config) }
            ?: Err(SettingsError.IOError("Истекло время сохранения настроек. Повторите попытку."))
}
