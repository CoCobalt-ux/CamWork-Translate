package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

internal fun CoroutineScope.observeMainWindowState(
    mainState: StateFlow<MainState>,
    settingsState: StateFlow<SettingsState>,
    handler: CoroutineExceptionHandler,
    render: (MainState, SettingsState) -> Unit
): Job = launch(Dispatchers.Swing + handler) {
    mainState.combine(settingsState) { main, settings -> main to settings }
        .distinctUntilChanged()
        .collect {
            // Читаем актуальный снимок в том же событии Swing, в котором меняем документ.
            render(mainState.value, settingsState.value)
        }
}
