package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.event.KeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntheticCopyModifierGateTest {
    @Test
    fun `Ctrl C разрешён когда физические модификаторы отпущены`() {
        val gate = SyntheticCopyModifierGate { false }

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
        val gate = SyntheticCopyModifierGate { false }

        gate.onNativeKeyPressed(NativeKeyEvent.VC_META)
        assertTrue(gate.hasPressedModifier())

        gate.onNativeKeyReleased(NativeKeyEvent.VC_META)
        assertFalse(gate.hasPressedModifier())

        gate.onNativeKeyPressed(NativeKeyEvent.VC_CONTROL)
        assertTrue(gate.hasPressedModifier())

        gate.reset()
        assertFalse(gate.hasPressedModifier())
    }
}
