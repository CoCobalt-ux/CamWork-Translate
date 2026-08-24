package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionTranslationHotkeyPolicyTest {

    @Test
    fun `дефолтный Shift обрабатывается безопасным нативным жестом`() {
        assertEquals(
            NativeKeyEvent.VC_SHIFT,
            HotkeyBinding.DEFAULT_SELECTION_TRANSLATION.nativeSelectionTapKeyCodeOrNull()
        )
    }

    @Test
    fun `небезопасная одиночная modifier клавиша не становится глобальным tap`() {
        val binding = HotkeyBinding(
            action = HotkeyAction.REPLACE_WITH_TRANSLATION,
            keyCode = KeyEvent.VK_ALT,
            modifiers = 0,
            scope = HotkeyScope.GLOBAL
        )

        assertNull(binding.nativeSelectionTapKeyCodeOrNull())
        assertTrue(binding.isModifierOnlySelectionBinding())
    }

    @Test
    fun `обычная пользовательская комбинация остаётся для Keymaster`() {
        val binding = HotkeyBinding(
            action = HotkeyAction.REPLACE_WITH_TRANSLATION,
            keyCode = KeyEvent.VK_F8,
            modifiers = InputEvent.CTRL_DOWN_MASK,
            scope = HotkeyScope.GLOBAL
        )

        assertNull(binding.nativeSelectionTapKeyCodeOrNull())
        assertFalse(binding.isModifierOnlySelectionBinding())
    }

    @Test
    fun `выключенная или локальная привязка не включает глобальный tap`() {
        assertNull(
            HotkeyBinding.DEFAULT_SELECTION_TRANSLATION.copy(isEnabled = false)
                .nativeSelectionTapKeyCodeOrNull()
        )
        assertNull(
            HotkeyBinding.DEFAULT_SELECTION_TRANSLATION.copy(scope = HotkeyScope.LOCAL)
                .nativeSelectionTapKeyCodeOrNull()
        )
    }
}
