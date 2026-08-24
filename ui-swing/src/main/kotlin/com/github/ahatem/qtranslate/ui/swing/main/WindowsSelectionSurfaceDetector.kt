package com.github.ahatem.qtranslate.ui.swing.main

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.OleAuto
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.Variant
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import java.awt.Point

/**
 * Определяет редактируемость выделения в чужом Windows-приложении.
 *
 * Сначала используется UI Automation: она видит Edit/Value/TextEdit в Chromium, Electron,
 * Qt и WinUI, где HWND один на всё окно. Если приложение не публикует accessibility-свойства,
 * используется GetGUIThreadInfo и класс сфокусированного Win32 control.
 */
internal class WindowsSelectionSurfaceDetector {
    fun detect(point: Point): SelectionSurface = inspect(point).surface

    /** Возвращает также системные признаки, не содержащие выделенный пользовательский текст. */
    fun inspect(
        point: Point,
        clipboard: ClipboardSelectionEvidence? = null
    ): SelectionSurfaceDetection {
        if (!isWindows()) {
            return SelectionSurfaceDetection(
                surface = classifyPortableSelectionSurface(clipboard),
                accessibility = null,
                win32 = null,
                clipboard = clipboard
            )
        }

        val accessibilityResult = runCatching { readAccessibilitySnapshot(point) }
        val win32Result = runCatching { readWin32Snapshot(point) }
        val accessibility = accessibilityResult.getOrNull()
        val win32 = win32Result.getOrNull()
        return SelectionSurfaceDetection(
            surface = classifySelectionSurface(accessibility, win32, clipboard),
            accessibility = accessibility,
            win32 = win32,
            clipboard = clipboard,
            accessibilityError = accessibilityResult.exceptionOrNull()?.diagnosticMessage(),
            win32Error = win32Result.exceptionOrNull()?.diagnosticMessage()
        )
    }

    private fun readAccessibilitySnapshot(point: Point): AccessibilitySelectionSnapshot? {
        val initializeResult = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
        val initializedHere = initializeResult.toInt() >= 0
        if (!initializedHere && initializeResult.toInt() != RPC_E_CHANGED_MODE) return null

        try {
            val automationReference = PointerByReference()
            val createResult = Ole32.INSTANCE.CoCreateInstance(
                C_UI_AUTOMATION,
                null,
                WTypes.CLSCTX_INPROC_SERVER,
                I_UI_AUTOMATION,
                automationReference
            )
            val automationPointer = automationReference.value
            if (createResult.failed() || automationPointer.isNullPointer()) return null

            val automation = AutomationClient(automationPointer)
            try {
                val elementReference = PointerByReference()
                val elementResult = automation.elementFromPoint(
                    WinDef.POINT.ByValue(point.x, point.y),
                    elementReference
                )
                val elementPointer = elementReference.value
                if (elementResult.failed() || elementPointer.isNullPointer()) return null

                val element = AutomationElement(elementPointer)
                try {
                    return AccessibilitySelectionSnapshot(
                        controlType = element.intProperty(UIA_CONTROL_TYPE_PROPERTY),
                        isEnabled = element.booleanProperty(UIA_IS_ENABLED_PROPERTY),
                        isValuePatternAvailable =
                            element.booleanProperty(UIA_IS_VALUE_PATTERN_AVAILABLE_PROPERTY),
                        isValueReadOnly = element.booleanProperty(UIA_VALUE_IS_READ_ONLY_PROPERTY),
                        isTextEditPatternAvailable =
                            element.booleanProperty(UIA_IS_TEXT_EDIT_PATTERN_AVAILABLE_PROPERTY),
                        isTextPatternAvailable =
                            element.booleanProperty(UIA_IS_TEXT_PATTERN_AVAILABLE_PROPERTY),
                        isKeyboardFocusable =
                            element.booleanProperty(UIA_IS_KEYBOARD_FOCUSABLE_PROPERTY),
                        hasKeyboardFocus =
                            element.booleanProperty(UIA_HAS_KEYBOARD_FOCUS_PROPERTY)
                    )
                } finally {
                    element.Release()
                }
            } finally {
                automation.Release()
            }
        } finally {
            if (initializedHere) Ole32.INSTANCE.CoUninitialize()
        }
    }

    private fun readWin32Snapshot(selectionPoint: Point): Win32SelectionSnapshot? {
        val user32 = User32.INSTANCE
        val foreground = user32.GetForegroundWindow()
        if (foreground.pointer.isNullPointer()) return null

        val threadId = user32.GetWindowThreadProcessId(foreground, null)
        if (threadId == 0) return null

        val info = WinUser.GUITHREADINFO().apply { cbSize = size() }
        if (!user32.GetGUIThreadInfo(threadId, info)) return null
        val focused = info.hwndFocus?.takeUnless { it.pointer.isNullPointer() } ?: foreground

        val classNameBuffer = CharArray(WINDOW_CLASS_BUFFER_SIZE)
        val classNameLength = user32.GetClassName(
            focused,
            classNameBuffer,
            classNameBuffer.size
        )
        val className = if (classNameLength > 0) Native.toString(classNameBuffer) else ""
        val focusedBounds = WinDef.RECT()
        val isSelectionInsideControl = user32.GetWindowRect(focused, focusedBounds) &&
            selectionPoint.x >= focusedBounds.left && selectionPoint.x < focusedBounds.right &&
            selectionPoint.y >= focusedBounds.top && selectionPoint.y < focusedBounds.bottom
        val caretWindow = info.hwndCaret?.takeUnless { it.pointer.isNullPointer() }
        val hasCaret = caretWindow != null
        val isCaretNearSelection = caretWindow?.let { window ->
            val windowBounds = WinDef.RECT()
            user32.GetWindowRect(window, windowBounds) &&
                Point(
                    windowBounds.left + info.rcCaret.left,
                    windowBounds.top + info.rcCaret.bottom
                ).distance(selectionPoint) <= CARET_PROXIMITY_PX
        } ?: false
        val isReadOnly = if (isStandardEditClass(className)) {
            user32.GetWindowLong(focused, WinUser.GWL_STYLE) and ES_READONLY != 0
        } else {
            null
        }

        return Win32SelectionSnapshot(
            className = className,
            hasCaret = hasCaret,
            isReadOnly = isReadOnly,
            isCaretNearSelection = isCaretNearSelection,
            isSelectionInsideControl = isSelectionInsideControl
        )
    }

    private class AutomationClient(pointer: Pointer) : Unknown(pointer) {
        fun elementFromPoint(
            point: WinDef.POINT.ByValue,
            result: PointerByReference
        ): WinNT.HRESULT = _invokeNativeObject(
            I_UI_AUTOMATION_ELEMENT_FROM_POINT_VTABLE_INDEX,
            arrayOf(pointer, point, result),
            WinNT.HRESULT::class.java
        ) as WinNT.HRESULT
    }

    private class AutomationElement(pointer: Pointer) : Unknown(pointer) {
        fun intProperty(propertyId: Int): Int? = property(propertyId) { value ->
            when (value.getVarType().toInt()) {
                Variant.VT_I4,
                Variant.VT_INT -> value.intValue()
                else -> null
            }
        }

        fun booleanProperty(propertyId: Int): Boolean? = property(propertyId) { value ->
            if (value.getVarType().toInt() == Variant.VT_BOOL) value.booleanValue() else null
        }

        private fun <T> property(
            propertyId: Int,
            read: (Variant.VARIANT.ByReference) -> T?
        ): T? {
            val value = Variant.VARIANT.ByReference()
            val result = _invokeNativeObject(
                I_UI_AUTOMATION_ELEMENT_GET_CURRENT_PROPERTY_VALUE_VTABLE_INDEX,
                arrayOf(pointer, propertyId, value),
                WinNT.HRESULT::class.java
            ) as WinNT.HRESULT
            if (result.failed()) return null

            return try {
                value.read()
                read(value)
            } finally {
                OleAuto.INSTANCE.VariantClear(value)
            }
        }
    }

    private fun Pointer?.isNullPointer(): Boolean =
        this == null || Pointer.nativeValue(this) == 0L

    private fun isStandardEditClass(className: String): Boolean {
        val normalized = className.lowercase()
        return normalized.contains("edit") ||
            normalized.contains("scintilla") ||
            normalized.contains("textbox") ||
            normalized.contains("tmemo")
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private companion object {
        val C_UI_AUTOMATION = Guid.CLSID("{FF48DBA4-60EF-4201-AA87-54103EEF594E}")
        val I_UI_AUTOMATION = Guid.IID("{30CBE57D-D9D0-452A-AB13-7AC5AC4825EE}")

        const val I_UI_AUTOMATION_ELEMENT_FROM_POINT_VTABLE_INDEX = 7
        const val I_UI_AUTOMATION_ELEMENT_GET_CURRENT_PROPERTY_VALUE_VTABLE_INDEX = 10

        const val UIA_CONTROL_TYPE_PROPERTY = 30_003
        const val UIA_HAS_KEYBOARD_FOCUS_PROPERTY = 30_008
        const val UIA_IS_KEYBOARD_FOCUSABLE_PROPERTY = 30_009
        const val UIA_IS_ENABLED_PROPERTY = 30_010
        const val UIA_IS_TEXT_PATTERN_AVAILABLE_PROPERTY = 30_040
        const val UIA_IS_VALUE_PATTERN_AVAILABLE_PROPERTY = 30_043
        const val UIA_VALUE_IS_READ_ONLY_PROPERTY = 30_046
        const val UIA_IS_TEXT_EDIT_PATTERN_AVAILABLE_PROPERTY = 30_149

        const val RPC_E_CHANGED_MODE = -2_147_417_850
        const val ES_READONLY = 0x0800
        const val WINDOW_CLASS_BUFFER_SIZE = 256
        const val CARET_PROXIMITY_PX = 160.0
    }
}

private fun Throwable.diagnosticMessage(): String =
    generateSequence(this) { it.cause }
        .joinToString(" <- ") { error ->
            "${error::class.java.simpleName}: ${error.message.orEmpty()}"
        }

private fun WinNT.HRESULT.failed(): Boolean = toInt() < 0
