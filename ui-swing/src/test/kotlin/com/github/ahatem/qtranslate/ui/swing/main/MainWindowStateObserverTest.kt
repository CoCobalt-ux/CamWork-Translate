package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainWindowStateObserverTest {
    @Test
    fun `запоздалое состояние перевода не перезаписывает свежий ввод редактора`() {
        val main = MutableStateFlow(MainState(inputText = "начало"))
        val settings = MutableStateFlow(SettingsState.initial(Configuration.DEFAULT))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val initialRender = CountDownLatch(1)
        val latestRender = CountDownLatch(1)
        val rendered = mutableListOf<String>()
        val edtEntered = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)

        scope.observeMainWindowState(main, settings, CoroutineExceptionHandler { _, error -> throw error }) { state, _ ->
            assertTrue(SwingUtilities.isEventDispatchThread())
            if (rendered.lastOrNull() != state.inputText) rendered.add(state.inputText)
            if (state.inputText == "начало") initialRender.countDown()
            if (state.inputText == "новая вставка") latestRender.countDown()
        }

        try {
            assertTrue(initialRender.await(5, TimeUnit.SECONDS))
            SwingUtilities.invokeLater {
                edtEntered.countDown()
                releaseEdt.await(5, TimeUnit.SECONDS)
                main.value = MainState(inputText = "новая вставка")
            }
            assertTrue(edtEntered.await(5, TimeUnit.SECONDS))
            main.value = MainState(inputText = "старый ответ")
            releaseEdt.countDown()
            assertTrue(latestRender.await(5, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait {
                assertEquals(listOf("начало", "новая вставка"), rendered)
            }
        } finally {
            releaseEdt.countDown()
            scope.cancel()
        }
    }
}
