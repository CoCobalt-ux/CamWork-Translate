package com.github.ahatem.qtranslate.core.plugin.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.io.File

/**
 * Persistent key-value storage scoped per plugin.
 *
 * Each plugin gets its own isolated DataStore file under `datastore/plugin_{id}.preferences_pb`.
 * Keys within a plugin's store are private to that plugin — there is no cross-plugin access.
 *
 * This is the backing store for [com.github.ahatem.qtranslate.core.plugin.ScopedPluginContext]'s
 * `storeValue` / `getValue` / `deleteValue` methods.
 */
class PluginKeyValueStore(private val appDataDirectory: File) : Closeable {

    private val dataStores = mutableMapOf<String, DataStore<Preferences>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    private fun getDataStore(pluginId: String): DataStore<Preferences> =
        dataStores.getOrPut(pluginId) {
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(appDataDirectory, "datastore/plugin_$pluginId.preferences_pb") }
            )
        }

    suspend fun getValue(pluginId: String, key: String): String? =
        getDataStore(pluginId).data.map { it[stringPreferencesKey(key)] }.first()

    suspend fun storeValue(pluginId: String, key: String, value: String) {
        getDataStore(pluginId).edit { it[stringPreferencesKey(key)] = value }
    }

    suspend fun storeValues(pluginId: String, values: Map<String, String>) {
        getDataStore(pluginId).edit { preferences ->
            values.forEach { (key, value) ->
                preferences[stringPreferencesKey(key)] = value
            }
        }
    }

    suspend fun deleteValue(pluginId: String, key: String) {
        getDataStore(pluginId).edit { it.remove(stringPreferencesKey(key)) }
    }

    suspend fun deleteAllData(pluginId: String) {
        getDataStore(pluginId).edit { it.clear() }
        // Очистка не завершает DataStore: следующий доступ должен использовать тот же экземпляр.
    }

    override fun close() = scope.cancel("Хранилища плагинов закрыты")
}
