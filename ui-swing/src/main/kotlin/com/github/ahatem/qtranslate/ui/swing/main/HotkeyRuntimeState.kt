package com.github.ahatem.qtranslate.ui.swing.main

/** Черновик настроек и пауза записи сочетания не подменяют сохранённый master-switch. */
internal class HotkeyRuntimeState {
    private var configuredEnabled = true
    private var suspensionDepth = 0

    @Volatile
    var isEnabled: Boolean = true
        private set

    @Synchronized
    fun configure(enabled: Boolean) {
        configuredEnabled = enabled
        refresh()
    }

    @Synchronized
    fun suspend() {
        suspensionDepth++
        refresh()
    }

    @Synchronized
    fun resume() {
        if (suspensionDepth > 0) suspensionDepth--
        refresh()
    }

    private fun refresh() {
        isEnabled = configuredEnabled && suspensionDepth == 0
    }
}
