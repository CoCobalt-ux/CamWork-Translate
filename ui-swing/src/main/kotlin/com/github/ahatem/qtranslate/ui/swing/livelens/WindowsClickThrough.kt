package com.github.ahatem.qtranslate.ui.swing.livelens

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Rectangle
import java.awt.Window

internal object WindowsClickThrough {
    fun setEnabled(window: Window, enabled: Boolean) {
        if (!isWindows() || !window.isDisplayable) return
        val handle = Native.getComponentPointer(window) ?: return
        val hwnd = com.sun.jna.platform.win32.WinDef.HWND(handle)
        val current = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        val mask = WS_EX_TRANSPARENT or WS_EX_NOACTIVATE or WS_EX_TOOLWINDOW
        val updated = if (enabled) current or mask else current and mask.inv()
        User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, updated)
    }

    /** Возвращает фактические пиксельные координаты окна для Windows UI Automation. */
    fun nativeBounds(window: Window): Rectangle? {
        if (!isWindows() || !window.isDisplayable) return null
        val handle = Native.getComponentPointer(window) ?: return null
        val rectangle = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(WinDef.HWND(handle), rectangle)) return null
        return Rectangle(
            rectangle.left,
            rectangle.top,
            rectangle.right - rectangle.left,
            rectangle.bottom - rectangle.top
        )
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private const val WS_EX_TRANSPARENT = 0x00000020
    private const val WS_EX_TOOLWINDOW = 0x00000080
    private const val WS_EX_NOACTIVATE = 0x08000000
}
