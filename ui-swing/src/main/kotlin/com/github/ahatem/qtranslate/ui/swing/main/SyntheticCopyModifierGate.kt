package com.github.ahatem.qtranslate.ui.swing.main

import com.sun.jna.platform.win32.User32
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Не даёт синтетическому Ctrl+C объединиться с физически зажатым модификатором.
 *
 * Для Chrome это критично: Ctrl+C, отправленный во время нажатого Shift, превращается
 * в Ctrl+Shift+C и открывает DevTools вместо чтения выделенного текста.
 */
internal class SyntheticCopyModifierGate(
    private val isKeyDown: (Int) -> Boolean = ::isWindowsVirtualKeyDown
) {
    private val pressedNativeModifiers = ConcurrentHashMap.newKeySet<Int>()

    fun onNativeKeyPressed(keyCode: Int) {
        if (keyCode in NATIVE_CONFLICTING_MODIFIERS) pressedNativeModifiers += keyCode
    }

    fun onNativeKeyReleased(keyCode: Int) {
        pressedNativeModifiers -= keyCode
    }

    fun reset() {
        pressedNativeModifiers.clear()
    }

    fun hasPressedModifier(): Boolean =
        pressedNativeModifiers.isNotEmpty() || COPY_CONFLICTING_MODIFIERS.any(isKeyDown)

    private companion object {
        val COPY_CONFLICTING_MODIFIERS = intArrayOf(
            KeyEvent.VK_SHIFT,
            KeyEvent.VK_CONTROL,
            KeyEvent.VK_ALT,
            WINDOWS_VK_LWIN,
            WINDOWS_VK_RWIN
        )
        val NATIVE_CONFLICTING_MODIFIERS = setOf(
            NativeKeyEvent.VC_SHIFT,
            NativeKeyEvent.VC_CONTROL,
            NativeKeyEvent.VC_ALT,
            NativeKeyEvent.VC_META
        )

        // java.awt.event.KeyEvent.VK_META не совпадает с Win32 VK_LWIN/VK_RWIN.
        const val WINDOWS_VK_LWIN = 0x5B
        const val WINDOWS_VK_RWIN = 0x5C
    }
}

private fun isWindowsVirtualKeyDown(keyCode: Int): Boolean {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return false

    return runCatching {
        User32.INSTANCE.GetAsyncKeyState(keyCode).toInt() and KEY_DOWN_MASK != 0
    }.getOrDefault(false)
}

private const val KEY_DOWN_MASK = 0x8000
