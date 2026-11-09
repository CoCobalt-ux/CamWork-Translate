package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ConfigurationPersistence
import com.github.ahatem.qtranslate.core.settings.data.SettingsError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSaveRecoveryTest {
    @Test
    fun `успех Применить не подменяет ошибку следующего ОК`() = runTest {
        val persistence = FakePersistence()
        val store = SettingsStore(persistence, logger, backgroundScope, Configuration.DEFAULT)
        runCurrent()
        store.dispatch(SettingsIntent.UpdateDraft(Configuration.DEFAULT.copy(uiScale = 125)))
        assertTrue(store.saveChanges().isOk)

        persistence.nextResult = Err(SettingsError.IOError("Отказ записи"))
        store.dispatch(SettingsIntent.UpdateDraft(store.state.value.workingConfiguration.copy(uiScale = 150)))
        val failed = store.saveChanges()
        assertTrue(failed.isErr)

        assertEquals("Отказ записи", failed.fold({ error("Ожидалась ошибка записи") }, { it.message }))
        assertEquals(125, store.state.value.originalConfiguration.uiScale)
        assertEquals(150, store.state.value.workingConfiguration.uiScale)
        assertTrue(store.state.value.isDirty)
        assertFalse(store.state.value.isSaving)
        assertNull(withTimeoutOrNull(1) { store.events.first() })
    }

    @Test
    fun `после отмены записи флаг сохранения освобождается и повтор работает`() = runTest {
        val persistence = FakePersistence()
        val store = SettingsStore(persistence, logger, backgroundScope, Configuration.DEFAULT)
        runCurrent()
        store.dispatch(SettingsIntent.UpdateDraft(Configuration.DEFAULT.copy(uiScale = 125)))
        persistence.gate = CompletableDeferred()
        val saving = launch { store.saveChanges() }
        runCurrent()
        assertTrue(store.state.value.isSaving)

        saving.cancelAndJoin()
        assertFalse(store.state.value.isSaving)
        assertTrue(store.state.value.isDirty)
        persistence.gate = null

        assertTrue(store.saveChanges().isOk)
        assertFalse(store.state.value.isDirty)
        assertEquals(125, store.state.value.originalConfiguration.uiScale)
    }

    @Test
    fun `исключение хранилища не блокирует последующие сохранения`() = runTest {
        val persistence = FakePersistence()
        val store = SettingsStore(persistence, logger, backgroundScope, Configuration.DEFAULT)
        runCurrent()
        store.dispatch(SettingsIntent.UpdateDraft(Configuration.DEFAULT.copy(uiScale = 125)))
        persistence.thrown = IllegalStateException("Временный отказ")

        assertTrue(store.saveChanges().isErr)
        assertFalse(store.state.value.isSaving)
        persistence.thrown = null
        assertTrue(store.saveChanges().isOk)
        assertFalse(store.state.value.isDirty)
    }

    @Test
    fun `много сохранений без подписчика не заполняют очередь результатов`() = runTest {
        val persistence = FakePersistence()
        val store = SettingsStore(persistence, logger, backgroundScope, Configuration.DEFAULT)
        runCurrent()
        repeat(150) { index ->
            store.dispatch(SettingsIntent.UpdateDraft(
                store.state.value.workingConfiguration.copy(uiScale = 100 + index)
            ))
            assertTrue(store.saveChanges().isOk)
        }
        assertEquals(249, store.state.value.originalConfiguration.uiScale)
        assertFalse(store.state.value.isSaving)
    }

    @Test
    fun `правки во время записи сохраняются в черновике до следующего подтверждения`() = runTest {
        val persistence = FakePersistence()
        val store = SettingsStore(persistence, logger, backgroundScope, Configuration.DEFAULT)
        runCurrent()
        store.dispatch(SettingsIntent.UpdateDraft(Configuration.DEFAULT.copy(uiScale = 125)))
        val gate = CompletableDeferred<Unit>()
        persistence.gate = gate
        val save = launch { store.saveChanges() }
        runCurrent()
        store.dispatch(SettingsIntent.UpdateDraft(store.state.value.workingConfiguration.copy(uiScale = 150)))
        gate.complete(Unit)
        save.join()
        runCurrent()
        assertEquals(125, store.state.value.originalConfiguration.uiScale)
        assertEquals(150, store.state.value.workingConfiguration.uiScale)
        assertTrue(store.state.value.isDirty)
    }

    private class FakePersistence : ConfigurationPersistence {
        override val configuration = MutableStateFlow(Configuration.DEFAULT)
        var nextResult: Result<Unit, SettingsError> = Ok(Unit)
        var gate: CompletableDeferred<Unit>? = null
        var thrown: Exception? = null

        override suspend fun updateConfiguration(config: Configuration): Result<Unit, SettingsError> {
            gate?.await()
            thrown?.let { throw it }
            if (nextResult.isOk) configuration.value = config
            return nextResult
        }
    }

    private companion object {
        val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
    }
}
