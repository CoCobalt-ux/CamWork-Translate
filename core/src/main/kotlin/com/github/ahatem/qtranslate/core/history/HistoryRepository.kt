package com.github.ahatem.qtranslate.core.history

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.shared.AppConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Единый владелец истории: параллельные переводы добавляют записи, а не заменяют чужие списки. */
class HistoryRepository(
    appDataDirectory: File,
    private val logger: Logger,
    private val json: Json,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    storage: DataStore<Preferences>? = null,
    private val storageTimeoutMillis: Long = 1_000L
) {
    init { require(storageTimeoutMillis > 0) }
    private val historyKey = stringPreferencesKey("translation_history_json")
    private val mutex = Mutex()
    private val epoch = AtomicLong(0)
    private var initialized = false
    private val _entries = MutableStateFlow<List<HistorySnapshot>>(emptyList())
    val entries: StateFlow<List<HistorySnapshot>> = _entries.asStateFlow()

    private val dataStore: DataStore<Preferences> = storage ?: PreferenceDataStoreFactory.create(
        scope = scope,
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { File(appDataDirectory, "datastore/${AppConstants.DATASTORE_HISTORY_FILE}") }
    )

    /** Запрос запоминает поколение до начала работы; очистка отзывает право старых запросов писать. */
    fun generation(): Long = epoch.get()

    suspend fun loadHistory(): List<HistorySnapshot> = mutex.withLock {
        initializeLocked()
        _entries.value
    }

    suspend fun append(snapshot: HistorySnapshot, expectedGeneration: Long): Boolean = mutex.withLock {
        if (!initializeLocked()) return@withLock false
        if (epoch.get() != expectedGeneration) return@withLock false
        commitLocked((_entries.value.filterNot { it.id == snapshot.id } + snapshot)
            .takeLast(AppConstants.MAX_HISTORY_ENTRIES))
        true
    }

    suspend fun updateExtraOutput(
        snapshotId: String,
        expectedGeneration: Long,
        text: String,
        type: String
    ): Boolean = mutex.withLock {
        if (!initializeLocked()) return@withLock false
        if (epoch.get() != expectedGeneration || _entries.value.none { it.id == snapshotId }) {
            return@withLock false
        }
        commitLocked(_entries.value.map { entry ->
            if (entry.id == snapshotId) entry.copy(extraOutputText = text, extraOutputType = type) else entry
        })
        true
    }

    /** Полная замена нужна для импорта; рабочие переводы используют только append/updateExtraOutput. */
    suspend fun saveHistory(history: List<HistorySnapshot>): Unit = mutex.withLock {
        epoch.incrementAndGet()
        initialized = true
        commitLocked(history.takeLast(AppConstants.MAX_HISTORY_ENTRIES))
        Unit
    }

    suspend fun clearHistory(): Boolean = mutex.withLock {
        epoch.incrementAndGet()
        initialized = true
        commitLocked(emptyList())
    }

    private suspend fun initializeLocked(): Boolean {
        if (initialized) return true
        val loaded = try {
            withTimeoutOrNull(storageTimeoutMillis) {
                dataStore.data.first()[historyKey]?.takeIf(String::isNotBlank)
                    ?.let { json.decodeFromString<List<HistorySnapshot>>(it) }
                    .orEmpty().takeLast(AppConstants.MAX_HISTORY_ENTRIES)
            } ?: run {
                logger.warn("История пока не прочитана: storageTimeoutMs=$storageTimeoutMillis; запись отложена")
                return false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error("Не удалось прочитать историю переводов", error)
            return false
        }
        _entries.value = loaded
        initialized = true
        return true
    }

    private suspend fun commitLocked(entries: List<HistorySnapshot>): Boolean {
        // Принятая операция дописывается до конца даже при отмене сетевого запроса рядом с ней.
        return withContext(NonCancellable) {
            var persisted = false
            try {
                persisted = withTimeoutOrNull(storageTimeoutMillis) {
                    dataStore.edit { preferences ->
                        if (entries.isEmpty()) preferences.clear()
                        else preferences[historyKey] = json.encodeToString(entries)
                    }
                    true
                } == true
                if (!persisted) logger.warn("История сохранена только в памяти: storageTimeoutMs=$storageTimeoutMillis")
            } catch (error: Exception) {
                logger.error("Не удалось сохранить историю переводов", error)
            }
            _entries.value = entries
            persisted
        }
    }
}
