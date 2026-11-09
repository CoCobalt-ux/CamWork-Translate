package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsError
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.testing.UiTestLogger
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StartupConfigurationPersistenceTest {
    @Test
    fun `отказ Windows возвращается реальному SettingsStore без сохранённой галки а повтор работает`() = runTest {
        StartupTestFixture().use { fixture ->
            val disk = StartupMemoryPersistence()
            val persistence = StartupConfigurationPersistence(disk, disk.configuration.value, fixture.startup)
            val store = SettingsStore(persistence, UiTestLogger(), backgroundScope, disk.configuration.value)
            runCurrent()
            store.dispatch(SettingsIntent.UpdateDraft(store.state.value.workingConfiguration.copy(launchOnSystemStartup = true)))
            fixture.registry.writeFailure = SecurityException("Отказ доступа")

            val failed = store.saveChanges()
            assertTrue(failed.isErr)
            assertFalse(store.state.value.originalConfiguration.launchOnSystemStartup)
            assertTrue(store.state.value.workingConfiguration.launchOnSystemStartup)
            assertTrue(store.state.value.isDirty)
            assertFalse(store.state.value.isSaving)
            assertEquals(0, disk.writes)

            fixture.registry.writeFailure = null
            assertTrue(store.saveChanges().isOk)
            runCurrent()
            assertTrue(store.state.value.originalConfiguration.launchOnSystemStartup)
            assertFalse(store.state.value.isDirty)
            assertEquals(fixture.command, fixture.registry.command)
        }
    }

    @Test
    fun `ошибка диска откатывает включение к прежней expandable команде и не блокирует повтор`() = runBlocking {
        StartupTestFixture().use { fixture ->
            val previous = StartupCommand("\"%LOCALAPPDATA%\\Другая копия\\CamWork Translate.exe\" --hidden", expandable = true)
            fixture.registry.command = previous
            val disk = StartupMemoryPersistence()
            val persistence = StartupConfigurationPersistence(disk, disk.configuration.value, fixture.startup)
            val enabled = disk.configuration.value.copy(launchOnSystemStartup = true)
            disk.result = Err(SettingsError.IOError("Недоступен диск"))
            assertTrue(persistence.updateConfiguration(enabled).isErr)
            assertEquals(previous, fixture.registry.command)
            assertFalse(disk.configuration.value.launchOnSystemStartup)
            disk.result = Ok(Unit)
            assertTrue(persistence.updateConfiguration(enabled).isOk)
            assertEquals(fixture.command, fixture.registry.command)
            assertTrue(disk.configuration.value.launchOnSystemStartup)
        }
    }

    @Test
    fun `ошибка выключения не сохраняет ложное отключение а после снятия отказа повтор работает`() = runBlocking {
        StartupTestFixture().use { fixture ->
            fixture.registry.command = fixture.command
            val disk = StartupMemoryPersistence(Configuration.DEFAULT.copy(launchOnSystemStartup = true))
            val persistence = StartupConfigurationPersistence(disk, disk.configuration.value, fixture.startup)
            val disabled = disk.configuration.value.copy(launchOnSystemStartup = false)
            fixture.registry.deletionFailure = SecurityException("Отказ удаления")
            assertTrue(persistence.updateConfiguration(disabled).isErr)
            assertTrue(disk.configuration.value.launchOnSystemStartup)
            assertEquals(fixture.command, fixture.registry.command)
            fixture.registry.deletionFailure = null
            assertTrue(persistence.updateConfiguration(disabled).isOk)
            assertNull(fixture.registry.command)
            assertFalse(disk.configuration.value.launchOnSystemStartup)
        }
    }

    @Test
    fun `startup reconciliation усыновляет installer запись без её перезаписи и сохраняет остальные настройки`() = runBlocking {
        StartupTestFixture().use { fixture ->
            fixture.registry.command = fixture.command
            val initial = Configuration.DEFAULT.copy(uiScale = 145, isHistoryEnabled = false)
            val disk = StartupMemoryPersistence(initial)
            val persistence = StartupConfigurationPersistence(disk, initial, fixture.startup)
            val result = persistence.reconcileAtLaunch()
            assertEquals(initial.copy(launchOnSystemStartup = true), result.configuration)
            assertEquals(result.configuration, disk.configuration.value)
            assertNull(result.warning)
            assertEquals(0, fixture.registry.writes)
        }
    }

    @Test
    fun `удалённая Run запись не воскресает из старой true галки но явно включается повторно`() = runBlocking {
        StartupTestFixture().use { fixture ->
            val disk = StartupMemoryPersistence(Configuration.DEFAULT.copy(launchOnSystemStartup = true, uiScale = 140))
            val persistence = StartupConfigurationPersistence(disk, disk.configuration.value, fixture.startup)
            val result = persistence.reconcileAtLaunch()
            assertFalse(result.configuration.launchOnSystemStartup)
            assertEquals(140, result.configuration.uiScale)
            assertEquals(StartupFailure.MISSING_ENTRY.message, result.warning)
            assertNull(fixture.registry.command)
            assertEquals(0, fixture.registry.writes)
            assertTrue(persistence.updateConfiguration(result.configuration.copy(launchOnSystemStartup = true)).isOk)
            assertEquals(fixture.command, fixture.registry.command)
        }
    }

    @Test
    fun `Windows disabled снимает сохранённую галку не удаляя Run и не изменяя approval`() = runBlocking {
        StartupTestFixture().use { fixture ->
            fixture.registry.command = fixture.command
            val approval = startupApproval(3)
            fixture.registry.approval = approval
            val disk = StartupMemoryPersistence(Configuration.DEFAULT.copy(launchOnSystemStartup = true))
            val persistence = StartupConfigurationPersistence(disk, disk.configuration.value, fixture.startup)
            val result = persistence.reconcileAtLaunch()
            assertFalse(result.configuration.launchOnSystemStartup)
            assertEquals(StartupFailure.DISABLED_BY_WINDOWS.message, result.warning)
            assertEquals(fixture.command, fixture.registry.command)
            assertTrue(approval.contentEquals(fixture.registry.approval))
            assertEquals(0, fixture.registry.deletes)
            assertTrue(persistence.updateConfiguration(result.configuration.copy(launchOnSystemStartup = true)).isErr)
            assertFalse(disk.configuration.value.launchOnSystemStartup)
            fixture.registry.approval = startupApproval(2)
            assertTrue(persistence.updateConfiguration(result.configuration.copy(launchOnSystemStartup = true)).isOk)
        }
    }

    @Test
    fun `чужая Run запись при старте не перехватывается и не удаляется`() = runBlocking {
        StartupTestFixture().use { fixture ->
            val foreign = StartupCommand("\"D:\\Другая папка\\CamWork Translate.exe\"")
            fixture.registry.command = foreign
            val disk = StartupMemoryPersistence(Configuration.DEFAULT.copy(launchOnSystemStartup = true))
            val persistence = StartupConfigurationPersistence(disk, disk.configuration.value, fixture.startup)
            val result = persistence.reconcileAtLaunch()
            assertFalse(result.configuration.launchOnSystemStartup)
            assertEquals(StartupFailure.OTHER_LAUNCHER.message, result.warning)
            assertEquals(foreign, fixture.registry.command)
            assertEquals(0, fixture.registry.writes)
            assertEquals(0, fixture.registry.deletes)
        }
    }

    @Test
    fun `отказ реестра и записи при сверке не возвращает старую true галку через repository flow`() = runBlocking {
        StartupTestFixture().use { fixture ->
            fixture.registry.readFailure = SecurityException("Нет доступа")
            val disk = StartupMemoryPersistence(Configuration.DEFAULT.copy(launchOnSystemStartup = true, uiScale = 125))
            disk.result = Err(SettingsError.IOError("Нет доступа к диску"))
            val persistence = StartupConfigurationPersistence(disk, disk.configuration.value, fixture.startup)
            val reconciled = persistence.reconcileAtLaunch()
            assertFalse(reconciled.configuration.launchOnSystemStartup)
            assertNotNull(reconciled.warning)
            assertTrue(reconciled.warning.contains("диску"))
            disk.configuration.value = disk.configuration.value.copy(uiScale = 150)
            val effective = persistence.configuration.first()
            assertFalse(effective.launchOnSystemStartup)
            assertEquals(150, effective.uiScale)
            fixture.registry.readFailure = null
            disk.result = Ok(Unit)
            assertTrue(persistence.updateConfiguration(effective.copy(launchOnSystemStartup = true)).isOk)
            assertTrue(persistence.configuration.first().launchOnSystemStartup)
        }
    }

    @Test
    fun `пропуск initial reconciliation не читает HKCU и пропускает позднюю настоящую конфигурацию`() = runBlocking {
        StartupTestFixture().use { fixture ->
            val disk = StartupMemoryPersistence()
            val persistence = StartupConfigurationPersistence(disk, Configuration.DEFAULT, fixture.startup)
            val lateConfiguration = Configuration.DEFAULT.copy(launchOnSystemStartup = true, uiScale = 145, isHistoryEnabled = false)
            disk.configuration.value = lateConfiguration
            assertEquals(lateConfiguration, persistence.configuration.first())
            assertEquals(0, disk.writes)
            assertEquals(0, fixture.registry.reads)
        }
    }

    @Test
    fun `зависшая запись ограничена сроком откатывает Run и освобождает следующий save`() = runBlocking {
        StartupTestFixture().use { fixture ->
            val disk = StartupMemoryPersistence()
            val persistence = StartupConfigurationPersistence(disk, disk.configuration.value, fixture.startup, timeoutMillis = 100)
            val enabled = disk.configuration.value.copy(launchOnSystemStartup = true)
            disk.hang = true
            val failed = withTimeout(5_000) { persistence.updateConfiguration(enabled) }
            assertTrue(failed.isErr)
            assertNull(fixture.registry.command)
            assertFalse(disk.configuration.value.launchOnSystemStartup)
            disk.hang = false
            assertTrue(withTimeout(5_000) { persistence.updateConfiguration(enabled) }.isOk)
            assertEquals(fixture.command, fixture.registry.command)
            assertTrue(disk.configuration.value.launchOnSystemStartup)
        }
    }

    @Test
    fun `закрытие диалога во время commit завершает согласованную операцию и не портит следующий save`() = runBlocking {
        StartupTestFixture().use { fixture ->
            val disk = StartupMemoryPersistence()
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            disk.gate = gate
            disk.entered = entered
            val persistence = StartupConfigurationPersistence(disk, disk.configuration.value, fixture.startup)
            val enabled = disk.configuration.value.copy(launchOnSystemStartup = true)
            val saving = launch { persistence.updateConfiguration(enabled) }
            withTimeout(5_000) { entered.await() }
            assertEquals(fixture.command, fixture.registry.command)
            saving.cancel()
            gate.complete(Unit)
            saving.join()
            assertTrue(disk.configuration.value.launchOnSystemStartup)
            assertEquals(fixture.command, fixture.registry.command)
            disk.gate = null
            assertTrue(persistence.updateConfiguration(enabled.copy(launchOnSystemStartup = false)).isOk)
            assertNull(fixture.registry.command)
        }
    }

    @Test
    fun `параллельные сохранения используют одну операцию реестра и остаются пригодны после нагрузки`() = runBlocking {
        StartupTestFixture().use { fixture ->
            val disk = StartupMemoryPersistence()
            val persistence = StartupConfigurationPersistence(disk, disk.configuration.value, fixture.startup)
            val enabled = disk.configuration.value.copy(launchOnSystemStartup = true)
            withTimeout(10_000) {
                List(40) { async { persistence.updateConfiguration(enabled) } }.awaitAll().forEach { assertTrue(it.isOk) }
            }
            assertEquals(1, fixture.registry.writes)
            assertTrue(persistence.updateConfiguration(enabled.copy(launchOnSystemStartup = false)).isOk)
            assertNull(fixture.registry.command)
            assertFalse(disk.configuration.value.launchOnSystemStartup)
        }
    }

    @Test
    fun `внешнее отключение Windows замечается даже при сохранении другого параметра`() = runBlocking {
        StartupTestFixture().use { fixture ->
            fixture.registry.command = fixture.command
            val disk = StartupMemoryPersistence(Configuration.DEFAULT.copy(launchOnSystemStartup = true))
            val persistence = StartupConfigurationPersistence(disk, disk.configuration.value, fixture.startup)
            fixture.registry.approval = startupApproval(3)
            val changed = disk.configuration.value.copy(uiScale = 125)
            assertTrue(persistence.updateConfiguration(changed).isErr)
            assertEquals(0, disk.writes)
            fixture.registry.approval = startupApproval(2)
            assertTrue(persistence.updateConfiguration(changed).isOk)
            assertEquals(125, disk.configuration.value.uiScale)
        }
    }
}
