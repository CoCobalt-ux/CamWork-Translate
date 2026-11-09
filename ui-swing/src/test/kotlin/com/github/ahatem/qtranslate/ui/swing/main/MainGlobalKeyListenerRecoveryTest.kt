package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ConfigurationPersistence
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.data.SettingsError
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.settings.panels.withHotkeysSuspended
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent
import com.github.kwhat.jnativehook.mouse.NativeMouseInputListener
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainGlobalKeyListenerRecoveryTest {
    @Test
    fun `черновик master false и Отмена после recorder не отключают оба канала выделения`() = runTest {
        val fixture = fixture()
        val persistence = object : ConfigurationPersistence {
            override val configuration = MutableStateFlow(Configuration.DEFAULT)
            override suspend fun updateConfiguration(config: Configuration): Result<Unit, SettingsError> = Ok(Unit)
        }
        val store = SettingsStore(persistence, fixture.logger, backgroundScope, Configuration.DEFAULT)
        backgroundScope.launch {
            store.state.map { it.originalConfiguration }.distinctUntilChanged().collect(fixture.listener::applyConfiguration)
        }
        runCurrent()
        fixture.listener.initialize()
        store.dispatch(SettingsIntent.UpdateDraft(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false)))
        runCurrent()
        withHotkeysSuspended(fixture.listener::suspendHotkeys, fixture.listener::resumeHotkeys) {
            fixture.backend.doubleClick()
        }
        store.dispatch(SettingsIntent.CancelChanges)
        runCurrent()

        fixture.backend.doubleClick()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("Выделенный текст"), fixture.overlays)
        fixture.listener.setAutoSelectionTranslateEnabled(false)
        fixture.backend.doubleClick()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("Выделенный текст"), fixture.buttons)
        fixture.listener.shutdown()
    }

    @Test
    fun `исключение recorder снимает временную паузу и сохраняет native hook`() = runTest {
        val fixture = fixture()
        fixture.listener.initialize()
        runCatching {
            withHotkeysSuspended(fixture.listener::suspendHotkeys, fixture.listener::resumeHotkeys) {
                assertTrue(fixture.backend.nativeRegistered)
                assertTrue(fixture.backend.registered.isEmpty())
                throw IllegalStateException("Управляемый отказ модального окна")
            }
        }
        fixture.backend.doubleClick()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, fixture.overlays.size)
        assertTrue(fixture.backend.registered.isNotEmpty())
        assertEquals(0, fixture.backend.nativeShutdowns)
        fixture.listener.shutdown()
    }

    @Test
    fun `вложенные паузы и сохранённое отключение не включают клавиши преждевременно`() = runTest {
        val fixture = fixture()
        fixture.listener.initialize()
        fixture.listener.suspendHotkeys()
        fixture.listener.suspendHotkeys()
        fixture.listener.resumeHotkeys()
        assertTrue(fixture.backend.registered.isEmpty())
        fixture.listener.applyConfiguration(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        fixture.listener.resumeHotkeys()
        fixture.listener.resumeHotkeys()
        assertTrue(fixture.backend.registered.isEmpty())
        assertTrue(fixture.backend.nativeRegistered)
        fixture.backend.doubleClick()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(fixture.overlays.isEmpty())
        fixture.listener.shutdown()
    }

    @Test
    fun `master false до initialize не теряется а запись клавиш сохраняет hook`() = runTest {
        val fixture = fixture()
        fixture.listener.setHotkeysEnabled(false)
        fixture.listener.initialize()
        assertEquals(0, fixture.backend.providerInitializations)
        assertTrue(fixture.backend.nativeRegistered)
        fixture.backend.doubleClick()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(fixture.overlays.isEmpty())
        fixture.listener.setHotkeysEnabled(true)
        fixture.backend.doubleClick()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, fixture.overlays.size)
        fixture.listener.shutdown()
    }

    @Test
    fun `изменение bindings при master false не захватывает глобальные сочетания`() = runTest {
        val fixture = fixture()
        fixture.listener.initialize()
        fixture.listener.setHotkeysEnabled(false)
        val binding = HotkeyBinding(HotkeyAction.SHOW_QUICK_TRANSLATE, KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK, scope = HotkeyScope.GLOBAL)
        fixture.listener.updateBindings(listOf(binding))
        assertTrue(fixture.backend.registered.isEmpty())
        fixture.listener.setHotkeysEnabled(true)
        assertEquals(setOf(binding.toKeyStroke()), fixture.backend.registered.keys)
        fixture.listener.shutdown()
    }

    @Test
    fun `отказ Keymaster не отключает кнопку и native наблюдение за мышью`() = runTest {
        val fixture = fixture()
        fixture.backend.providerFailures = 1
        fixture.listener.initialize()
        assertTrue(fixture.backend.nativeRegistered)
        fixture.listener.setAutoSelectionTranslateEnabled(false)
        fixture.backend.doubleClick()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, fixture.buttons.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(fixture.backend.registered.isNotEmpty())
        assertEquals(2, fixture.backend.providerInitializations)
        fixture.listener.shutdown()
    }

    @Test
    fun `первый отказ native hook восстанавливается без переключения чекбокса`() = runTest {
        val fixture = fixture()
        fixture.backend.nativeFailures = 1
        fixture.listener.initialize()
        assertFalse(fixture.backend.nativeRegistered)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(fixture.backend.nativeRegistered)
        fixture.backend.doubleClick()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, fixture.overlays.size)
        fixture.listener.shutdown()
    }

    @Test
    fun `потерянная native регистрация повторно подключает мышь и клавиатуру`() = runTest {
        val fixture = fixture()
        fixture.listener.initialize()
        fixture.backend.nativeRegistered = false
        fixture.backend.mouse = null
        fixture.backend.keyboard = null
        advanceTimeBy(1_000)
        runCurrent()
        fixture.backend.doubleClick()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, fixture.overlays.size)
        assertTrue(fixture.backend.keyboard != null)
        fixture.listener.shutdown()
    }

    @Test
    fun `отказ reset пересоздаёт provider вместо вечного пустого списка сочетаний`() = runTest {
        val fixture = fixture()
        fixture.listener.initialize()
        fixture.backend.resetFailures = 1
        fixture.listener.updateBindings(listOf(HotkeyBinding(HotkeyAction.SHOW_IMAGES, KeyEvent.VK_F10)))
        assertEquals(1, fixture.backend.providerShutdowns)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, fixture.backend.providerInitializations)
        assertEquals(1, fixture.backend.registered.size)
        fixture.listener.shutdown()
    }

    @Test
    fun `отказ одного register повторяется без сброса остальных сочетаний`() = runTest {
        val fixture = fixture()
        fixture.backend.registrationFailures = 1
        fixture.listener.initialize()
        val afterFailure = fixture.backend.registered.size
        val resets = fixture.backend.resets
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(afterFailure + 1, fixture.backend.registered.size)
        assertEquals(resets, fixture.backend.resets)
        fixture.listener.shutdown()
    }

    @Test
    fun `ошибка одного захвата не отменяет следующие выделения`() = runTest {
        var captures = 0
        val fixture = fixture(capture = {
            if (++captures == 1) throw IllegalStateException("Управляемый отказ clipboard")
            SelectionCaptureSnapshot("Следующий текст")
        })
        fixture.listener.initialize()
        repeat(2) {
            fixture.backend.doubleClick()
            advanceTimeBy(500)
            runCurrent()
        }
        assertEquals(listOf("Следующий текст"), fixture.overlays)
        assertTrue(fixture.logger.failures > 0)
        fixture.listener.shutdown()
    }

    @Test
    fun `ошибка callback мыши не вырывается в поток native dispatch`() = runTest {
        val fixture = fixture(onPointerPressed = { throw IllegalStateException("Управляемый отказ callback") })
        fixture.listener.initialize()
        fixture.backend.mouse!!.nativeMousePressed(mouseEvent(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 1))
        fixture.backend.doubleClick()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, fixture.overlays.size)
        assertTrue(fixture.logger.failures > 0)
        fixture.listener.shutdown()
    }

    @Test
    fun `legacy icon false и выключенный auto всё равно дают безопасную кнопку`() = runTest {
        val fixture = fixture()
        fixture.listener.applyConfiguration(Configuration.DEFAULT.copy(isSelectionIconEnabled = false, isAutoSelectionTranslateEnabled = false))
        fixture.listener.initialize()
        fixture.backend.doubleClick()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, fixture.buttons.size)
        assertTrue(fixture.overlays.isEmpty())
        fixture.listener.shutdown()
    }

    @Test
    fun `ошибка освобождения provider не пропускает закрытие hook и watchdog`() = runTest {
        val fixture = fixture()
        fixture.listener.initialize()
        fixture.backend.shutdownFailure = true
        fixture.listener.shutdown()
        val calls = fixture.backend.nativeEnsures
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, fixture.backend.nativeShutdowns)
        assertEquals(calls, fixture.backend.nativeEnsures)
        assertFalse(fixture.backend.nativeRegistered)
    }

    private fun TestScope.fixture(
        capture: suspend () -> SelectionCaptureSnapshot = { SelectionCaptureSnapshot("Выделенный текст") },
        onPointerPressed: (Point) -> Unit = {}
    ): Fixture {
        val fixture = Fixture()
        fixture.listener = MainGlobalKeyListener(
            scope = backgroundScope, logger = fixture.logger,
            onShowApp = {}, onShowQuickTranslate = {}, onShiftTapTranslate = { _, _ -> },
            onListenToText = {}, onOpenSnippingTool = {}, onReplaceWithTranslation = {}, onCycleTargetLanguage = {},
            onSelectionDetected = { text, _ -> fixture.buttons += text },
            onAutoTranslateSelection = { text, _ -> fixture.overlays += text },
            onPointerPressed = onPointerPressed,
            inputBackend = fixture.backend, recoveryIntervalMillis = 1_000,
            captureSource = SelectionCaptureSource { capture() },
            surfaceReader = SelectionSurfaceReader { _, _ -> SelectionSurfaceDetection(SelectionSurface.READ_ONLY, null, null) }
        )
        return fixture
    }

    private class Fixture {
        val backend = FakeBackend()
        val logger = TestLogger()
        val buttons = mutableListOf<String>()
        val overlays = mutableListOf<String>()
        lateinit var listener: MainGlobalKeyListener
    }

    private class FakeBackend : GlobalInputBackend {
        var providerFailures = 0
        var nativeFailures = 0
        var resetFailures = 0
        var registrationFailures = 0
        var shutdownFailure = false
        var providerInitializations = 0
        var providerShutdowns = 0
        var nativeShutdowns = 0
        var nativeEnsures = 0
        var resets = 0
        var nativeRegistered = false
        var keyboard: NativeKeyListener? = null
        var mouse: NativeMouseInputListener? = null
        val registered = linkedMapOf<KeyStroke, () -> Unit>()
        override fun initializeHotkeys() {
            providerInitializations++
            if (providerFailures-- > 0) throw IllegalStateException("Отказ Keymaster")
        }
        override fun resetHotkeys() {
            resets++
            if (resetFailures-- > 0) throw IllegalStateException("Отказ reset")
            registered.clear()
        }
        override fun registerHotkey(keyStroke: KeyStroke, onPressed: () -> Unit) {
            if (registrationFailures-- > 0) throw IllegalStateException("Отказ register")
            registered[keyStroke] = onPressed
        }
        override fun shutdownHotkeys() {
            providerShutdowns++
            registered.clear()
            if (shutdownFailure) throw IllegalStateException("Отказ освобождения provider")
        }
        override fun isNativeHookRegistered(): Boolean = nativeRegistered
        override fun ensureNativeHook(keyboard: NativeKeyListener, mouse: NativeMouseInputListener) {
            nativeEnsures++
            if (nativeFailures-- > 0) throw IllegalStateException("Отказ native hook")
            nativeRegistered = true
            this.keyboard = keyboard
            this.mouse = mouse
        }
        override fun shutdownNativeHook(keyboard: NativeKeyListener, mouse: NativeMouseInputListener) {
            nativeShutdowns++
            nativeRegistered = false
            this.keyboard = null
            this.mouse = null
        }
        fun doubleClick() {
            mouse?.nativeMouseClicked(mouseEvent(NativeMouseEvent.NATIVE_MOUSE_CLICKED, 2))
        }
    }

    private class TestLogger : Logger {
        var failures = 0
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) { failures++ }
    }

    private companion object {
        fun mouseEvent(id: Int, clicks: Int) = NativeMouseEvent(id, 0, 100, 100, clicks, NativeMouseEvent.BUTTON1)
    }
}
