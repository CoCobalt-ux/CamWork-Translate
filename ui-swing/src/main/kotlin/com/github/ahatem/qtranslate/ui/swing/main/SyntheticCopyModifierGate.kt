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
    private val reconcileNativeState: Boolean = System.getProperty("os.name")
        .orEmpty().startsWith("Windows", ignoreCase = true),
    private val nowNanos: () -> Long = System::nanoTime,
    private val isKeyDown: (Int) -> Boolean? = ::isWindowsVirtualKeyDown
) {
    private val pressedNativeModifiers = ConcurrentHashMap<Int, Long>()

    fun onNativeKeyPressed(keyCode: Int) {
        if (keyCode in NATIVE_CONFLICTING_MODIFIERS) {
            pressedNativeModifiers.putIfAbsent(keyCode, nowNanos())
        }
    }

    fun onNativeKeyReleased(keyCode: Int) {
        pressedNativeModifiers.remove(keyCode)
    }

    fun reset() {
        pressedNativeModifiers.clear()
    }

    fun hasPressedModifier(): Boolean {
        val physicalState = COPY_CONFLICTING_MODIFIERS.associateWith(isKeyDown)
        if (reconcileNativeState) {
            val now = nowNanos()
            pressedNativeModifiers.entries.removeIf { (nativeCode, pressedAt) ->
                // После блокировки экрана или RDP native-hook может потерять keyRelease.
                // Сверяем только достоверно отпущенные клавиши; ошибка Win32 не снимает защиту.
                now - pressedAt >= NATIVE_EVENT_GRACE_NANOS &&
                    NATIVE_TO_VIRTUAL.getValue(nativeCode).all { physicalState[it] == false }
            }
        }
        return pressedNativeModifiers.isNotEmpty() || physicalState.values.any { it == true }
    }

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
        val NATIVE_TO_VIRTUAL = mapOf(
            NativeKeyEvent.VC_SHIFT to intArrayOf(KeyEvent.VK_SHIFT),
            NativeKeyEvent.VC_CONTROL to intArrayOf(KeyEvent.VK_CONTROL),
            NativeKeyEvent.VC_ALT to intArrayOf(KeyEvent.VK_ALT),
            NativeKeyEvent.VC_META to intArrayOf(WINDOWS_VK_LWIN, WINDOWS_VK_RWIN)
        )
        const val NATIVE_EVENT_GRACE_NANOS = 150_000_000L

        // java.awt.event.KeyEvent.VK_META не совпадает с Win32 VK_LWIN/VK_RWIN.
        const val WINDOWS_VK_LWIN = 0x5B
        const val WINDOWS_VK_RWIN = 0x5C
    }
}

private fun isWindowsVirtualKeyDown(keyCode: Int): Boolean? {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return null

    return runCatching {
        User32.INSTANCE.GetAsyncKeyState(keyCode).toInt() and KEY_DOWN_MASK != 0
    }.getOrNull()
}

private const val KEY_DOWN_MASK = 0x8000
