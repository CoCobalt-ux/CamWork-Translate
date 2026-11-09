package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.event.KeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntheticCopyModifierGateTest {
    @Test
    fun `Ctrl C разрешён когда физические модификаторы отпущены`() {
        val gate = SyntheticCopyModifierGate(reconcileNativeState = false) { false }

        assertFalse(gate.hasPressedModifier())
    }

    @Test
    fun `Shift блокирует Ctrl C чтобы Chrome не открыл DevTools`() {
        val gate = SyntheticCopyModifierGate { it == KeyEvent.VK_SHIFT }

        assertTrue(gate.hasPressedModifier())
    }

    @Test
    fun `прочие системные модификаторы также блокируют синтетическое копирование`() {
        val modifiers = setOf(
            KeyEvent.VK_CONTROL,
            KeyEvent.VK_ALT,
            0x5B,
            0x5C
        )

        modifiers.forEach { pressed ->
            val gate = SyntheticCopyModifierGate { it == pressed }
            assertTrue(gate.hasPressedModifier(), "Модификатор $pressed должен блокировать Ctrl+C")
        }
    }

    @Test
    fun `нативные события защищают Cmd и Ctrl на macOS без Win32`() {
        val gate = SyntheticCopyModifierGate(reconcileNativeState = false) { false }

        gate.onNativeKeyPressed(NativeKeyEvent.VC_META)
        assertTrue(gate.hasPressedModifier())

        gate.onNativeKeyReleased(NativeKeyEvent.VC_META)
        assertFalse(gate.hasPressedModifier())

        gate.onNativeKeyPressed(NativeKeyEvent.VC_CONTROL)
        assertTrue(gate.hasPressedModifier())

        gate.reset()
        assertFalse(gate.hasPressedModifier())
    }

    @Test
    fun `потерянный keyRelease не отключает перевод до перезапуска`() {
        var now = 0L
        val gate = SyntheticCopyModifierGate(reconcileNativeState = true, nowNanos = { now }) { false }
        repeat(1_000) {
            gate.onNativeKeyPressed(NativeKeyEvent.VC_CONTROL)
            assertTrue(gate.hasPressedModifier())
            now += 200_000_000L
            assertFalse(gate.hasPressedModifier(), "Windows подтвердила отпускание Ctrl")
        }
    }

    @Test
    fun `физически удерживаемый Ctrl остается защищен после пропуска событий`() {
        var now = 0L
        var physicallyDown = true
        val gate = SyntheticCopyModifierGate(reconcileNativeState = true, nowNanos = { now }) {
            it == KeyEvent.VK_CONTROL && physicallyDown
        }
        gate.onNativeKeyPressed(NativeKeyEvent.VC_CONTROL)
        now = 60_000_000_000L
        assertTrue(gate.hasPressedModifier())
        physicallyDown = false
        assertFalse(gate.hasPressedModifier())
    }

    @Test
    fun `сбой чтения Win32 не снимает защиту нативного модификатора`() {
        var now = 0L
        val gate = SyntheticCopyModifierGate(reconcileNativeState = true, nowNanos = { now }) { null }
        gate.onNativeKeyPressed(NativeKeyEvent.VC_SHIFT)
        now = 60_000_000_000L
        assertTrue(gate.hasPressedModifier())
        gate.onNativeKeyReleased(NativeKeyEvent.VC_SHIFT)
        assertFalse(gate.hasPressedModifier())
    }
}
