package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.MouseInfo
import java.awt.Point
import com.sun.jna.platform.win32.WinDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionSurfacePolicyTest {

    @Test
    fun `portable HTML выделение считается опубликованным текстом`() {
        assertEquals(
            SelectionSurface.READ_ONLY,
            classifyPortableSelectionSurface(
                ClipboardSelectionEvidence(hasHtml = true, hasEditableMarkup = false)
            )
        )
    }

    @Test
    fun `portable editable HTML остаётся полем ввода`() {
        assertEquals(
            SelectionSurface.EDITABLE,
            classifyPortableSelectionSurface(
                ClipboardSelectionEvidence(hasHtml = true, hasEditableMarkup = true)
            )
        )
    }

    @Test
    fun `portable plain text не запускает автоматический popup без доказательств`() {
        assertEquals(
            SelectionSurface.UNKNOWN,
            classifyPortableSelectionSurface(
                ClipboardSelectionEvidence(hasHtml = false, hasEditableMarkup = false)
            )
        )
    }
    @Test
    fun `UI Automation Value с разрешённым редактированием определяется как поле ввода`() {
        val result = classifySelectionSurface(
            accessibility = accessibility(
                controlType = 50_004,
                isValuePatternAvailable = true,
                isValueReadOnly = false
            ),
            win32 = null
        )

        assertEquals(SelectionSurface.EDITABLE, result)
    }

    @Test
    fun `неполный Value snapshot не разрешает автоматический popup`() {
        val result = classifySelectionSurface(
            accessibility = accessibility(
                controlType = 50_025,
                isValuePatternAvailable = true,
                isValueReadOnly = null
            ),
            win32 = null
        )

        assertEquals(SelectionSurface.UNKNOWN, result)
    }

    @Test
    fun `UI Automation TextEdit определяет contenteditable как поле ввода`() {
        val result = classifySelectionSurface(
            accessibility = accessibility(
                controlType = 50_030,
                isTextEditPatternAvailable = true
            ),
            win32 = null
        )

        assertEquals(SelectionSurface.EDITABLE, result)
    }

    @Test
    fun `обычный опубликованный Text определяется как нередактируемый`() {
        val result = classifySelectionSurface(
            accessibility = accessibility(controlType = 50_020),
            win32 = Win32SelectionSnapshot("Chrome_RenderWidgetHostHWND", false, null)
        )

        assertEquals(SelectionSurface.READ_ONLY, result)
    }

    @Test
    fun `кастомный UIA TextPattern в Chromium считается опубликованным текстом`() {
        val result = classifySelectionSurface(
            accessibility = accessibility(
                controlType = 50_025,
                isTextPatternAvailable = true
            ),
            win32 = Win32SelectionSnapshot("Chrome_RenderWidgetHostHWND", true, null, false)
        )

        assertEquals(SelectionSurface.READ_ONLY, result)
    }

    @Test
    fun `явно нефокусируемый Custom TextPattern считается опубликованным текстом`() {
        val result = classifySelectionSurface(
            accessibility = accessibility(
                controlType = 50_025,
                isTextPatternAvailable = true,
                isKeyboardFocusable = false
            ),
            win32 = null
        )

        assertEquals(SelectionSurface.READ_ONLY, result)
    }

    @Test
    fun `фокус корня Chromium Document не превращает опубликованный текст в поле ввода`() {
        val result = classifySelectionSurface(
            accessibility = accessibility(
                controlType = 50_025,
                isTextPatternAvailable = true,
                isKeyboardFocusable = true,
                hasKeyboardFocus = true
            ),
            win32 = null
        )

        assertEquals(SelectionSurface.READ_ONLY, result)
    }

    @Test
    fun `UIA Document без признака редактирования считается опубликованным текстом`() {
        val result = classifySelectionSurface(
            accessibility = accessibility(
                controlType = 50_030,
                isTextPatternAvailable = true
            ),
            win32 = null
        )

        assertEquals(SelectionSurface.READ_ONLY, result)
    }

    @Test
    fun `caret выделения Chromium не превращает опубликованный TextPattern в поле ввода`() {
        val result = classifySelectionSurface(
            accessibility = accessibility(
                controlType = 50_030,
                isTextPatternAvailable = true,
                isKeyboardFocusable = true,
                hasKeyboardFocus = true
            ),
            win32 = Win32SelectionSnapshot(
                className = "Chrome_RenderWidgetHostHWND",
                hasCaret = true,
                isReadOnly = null,
                isCaretNearSelection = true
            )
        )

        assertEquals(SelectionSurface.READ_ONLY, result)
    }

    @Test
    fun `явный Win32 Edit имеет приоритет над дочерним UIA Text`() {
        val result = classifySelectionSurface(
            accessibility = accessibility(
                controlType = 50_020,
                isTextPatternAvailable = true
            ),
            win32 = Win32SelectionSnapshot(
                className = "RichEdit50W",
                hasCaret = true,
                isReadOnly = false,
                isCaretNearSelection = true,
                isSelectionInsideControl = true
            )
        )

        assertEquals(SelectionSurface.EDITABLE, result)
    }

    @Test
    fun `зелёный трей автоматически открывает перевод Chromium Document`() {
        val surface = classifySelectionSurface(
            accessibility = accessibility(
                controlType = 50_030,
                isTextPatternAvailable = true,
                isKeyboardFocusable = true,
                hasKeyboardFocus = true
            ),
            win32 = Win32SelectionSnapshot(
                className = "Chrome_RenderWidgetHostHWND",
                hasCaret = true,
                isReadOnly = null,
                isCaretNearSelection = false
            )
        )

        assertEquals(SelectionSurface.READ_ONLY, surface)
        assertEquals(
            SelectionPresentation.AUTO_OVERLAY,
            resolveSelectionPresentation(surface, isAutoOverlayEnabled = true)
        )
    }

    @Test
    fun `Win32 caret служит fallback для нестандартного поля ввода`() {
        val result = classifySelectionSurface(
            accessibility = null,
            win32 = Win32SelectionSnapshot("CustomControl", hasCaret = true, isReadOnly = null)
        )

        assertEquals(SelectionSurface.EDITABLE, result)
    }

    @Test
    fun `далёкий caret поля чата не превращает опубликованное сообщение в input`() {
        val result = classifySelectionSurface(
            accessibility = null,
            win32 = Win32SelectionSnapshot(
                className = "Chrome_RenderWidgetHostHWND",
                hasCaret = true,
                isReadOnly = null,
                isCaretNearSelection = false
            )
        )

        assertEquals(SelectionSurface.UNKNOWN, result)
    }

    @Test
    fun `read only Win32 Edit не считается полем ввода`() {
        val result = classifySelectionSurface(
            accessibility = null,
            win32 = Win32SelectionSnapshot("RichEdit50W", hasCaret = true, isReadOnly = true)
        )

        assertEquals(SelectionSurface.READ_ONLY, result)
    }

    @Test
    fun `сфокусированный Edit вне точки выделения не описывает опубликованный текст`() {
        val result = classifySelectionSurface(
            accessibility = null,
            win32 = Win32SelectionSnapshot(
                className = "RichEdit50W",
                hasCaret = true,
                isReadOnly = false,
                isCaretNearSelection = false,
                isSelectionInsideControl = false
            )
        )

        assertEquals(SelectionSurface.UNKNOWN, result)
    }

    @Test
    fun `surface и tray toggle точно соответствуют безопасной матрице`() {
        data class Case(
            val surface: SelectionSurface,
            val autoOverlayEnabled: Boolean,
            val expected: SelectionPresentation
        )

        val cases = listOf(
            Case(SelectionSurface.EDITABLE, false, SelectionPresentation.MINI_BUTTON),
            Case(SelectionSurface.EDITABLE, true, SelectionPresentation.MINI_BUTTON),
            Case(SelectionSurface.READ_ONLY, false, SelectionPresentation.MINI_BUTTON),
            Case(SelectionSurface.READ_ONLY, true, SelectionPresentation.AUTO_OVERLAY),
            Case(SelectionSurface.UNKNOWN, false, SelectionPresentation.MINI_BUTTON),
            Case(SelectionSurface.UNKNOWN, true, SelectionPresentation.MINI_BUTTON)
        )

        cases.forEach { case ->
            assertEquals(
                case.expected,
                resolveSelectionPresentation(case.surface, case.autoOverlayEnabled),
                "surface=${case.surface}, autoOverlayEnabled=${case.autoOverlayEnabled}"
            )
        }
    }

    @Test
    fun `UNKNOWN без доступной accessibility оставляет mini button при зелёном трее`() {
        assertEquals(
            SelectionPresentation.MINI_BUTTON,
            resolveSelectionPresentation(
                surface = SelectionSurface.UNKNOWN,
                isAutoOverlayEnabled = true
            )
        )
    }

    @Test
    fun `HTML clipboard разрешает автоокно при отключённой accessibility браузера`() {
        val surface = classifySelectionSurface(
            accessibility = null,
            win32 = Win32SelectionSnapshot(
                className = "Chrome_WidgetWin_1",
                hasCaret = false,
                isReadOnly = null
            ),
            clipboard = ClipboardSelectionEvidence(hasHtml = true, hasEditableMarkup = false)
        )

        assertEquals(SelectionSurface.READ_ONLY, surface)
        assertEquals(
            SelectionPresentation.AUTO_OVERLAY,
            resolveSelectionPresentation(surface, isAutoOverlayEnabled = true)
        )
    }

    @Test
    fun `явный contenteditable clipboard запрещает автоокно без UIA`() {
        val surface = classifySelectionSurface(
            accessibility = null,
            win32 = Win32SelectionSnapshot("Chrome_WidgetWin_1", false, null),
            clipboard = ClipboardSelectionEvidence(hasHtml = true, hasEditableMarkup = true)
        )

        assertEquals(SelectionSurface.EDITABLE, surface)
        assertEquals(
            SelectionPresentation.MINI_BUTTON,
            resolveSelectionPresentation(surface, isAutoOverlayEnabled = true)
        )
    }

    @Test
    fun `публичный HTML сильнее слабого Chromium caret при выключенной UIA`() {
        val surface = classifySelectionSurface(
            accessibility = null,
            win32 = Win32SelectionSnapshot(
                className = "Chrome_WidgetWin_1",
                hasCaret = true,
                isReadOnly = null,
                isCaretNearSelection = true
            ),
            clipboard = ClipboardSelectionEvidence(hasHtml = true, hasEditableMarkup = false)
        )

        assertEquals(SelectionSurface.READ_ONLY, surface)
    }

    @Test
    fun `явный нативный Edit сильнее HTML clipboard`() {
        val surface = classifySelectionSurface(
            accessibility = null,
            win32 = Win32SelectionSnapshot(
                className = "RichEdit50W",
                hasCaret = true,
                isReadOnly = false
            ),
            clipboard = ClipboardSelectionEvidence(hasHtml = true, hasEditableMarkup = false)
        )

        assertEquals(SelectionSurface.EDITABLE, surface)
    }

    @Test
    fun `HTML неизвестного не Chromium приложения не разрешает навязчивое автоокно`() {
        val surface = classifySelectionSurface(
            accessibility = null,
            win32 = Win32SelectionSnapshot("UnknownCanvas", false, null),
            clipboard = ClipboardSelectionEvidence(hasHtml = true, hasEditableMarkup = false)
        )

        assertEquals(SelectionSurface.UNKNOWN, surface)
        assertEquals(
            SelectionPresentation.MINI_BUTTON,
            resolveSelectionPresentation(surface, isAutoOverlayEnabled = true)
        )
    }

    @Test
    fun `JNA может записать публичную UIA POINT структуру`() {
        val point = WinDef.POINT.ByValue(120, 240)

        point.write()

        assertEquals(8, point.size())
        assertEquals(120, point.x)
        assertEquals(240, point.y)
    }

    @Test
    fun `UNKNOWN при сером трее оставляет mini button`() {
        assertEquals(
            SelectionPresentation.MINI_BUTTON,
            resolveSelectionPresentation(
                surface = SelectionSurface.UNKNOWN,
                isAutoOverlayEnabled = false
            )
        )
    }

    @Test
    fun `AWT снимок курсора имеет приоритет над physical native point`() {
        assertEquals(
            Point(1_600, 800),
            resolveAwtScreenPoint(
                nativePoint = Point(2_400, 1_200),
                awtPointerSnapshot = Point(1_600, 800)
            )
        )
    }

    @Test
    fun `native point остаётся безопасным fallback без MouseInfo`() {
        assertEquals(Point(-700, 400), resolveAwtScreenPoint(Point(-700, 400), null))
    }

    @Test
    fun `READ ONLY presentation запускает auto callback с исходным текстом`() {
        val automatic = mutableListOf<String>()
        var buttonCalls = 0

        deliverSelectionPresentation(
            text = "published text",
            pointer = Point(240, 180),
            presentation = SelectionPresentation.AUTO_OVERLAY,
            onMiniButton = { _, _ -> buttonCalls++ },
            onAutoOverlay = automatic::add
        )

        assertEquals(listOf("published text"), automatic)
        assertEquals(0, buttonCalls)
    }

    @Test
    fun `EDITABLE presentation сохраняет текст и точку для обязательной кнопки`() {
        var delivered: Pair<String, Point>? = null

        deliverSelectionPresentation(
            text = "draft message",
            pointer = Point(320, 210),
            presentation = SelectionPresentation.MINI_BUTTON,
            onMiniButton = { text, point -> delivered = text to point },
            onAutoOverlay = { error("Не должен запускаться auto overlay") }
        )

        assertEquals("draft message" to Point(320, 210), delivered)
    }

    @Test
    fun `Windows detector безопасно опрашивает настоящий элемент под курсором`() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return

        val pointer = MouseInfo.getPointerInfo()?.location ?: java.awt.Point(0, 0)
        val result = WindowsSelectionSurfaceDetector().detect(pointer)

        assertTrue(result in SelectionSurface.entries)
    }


    @Test
    fun `форк Chromium с собственным классом окна тоже даёт автоокно`() {
        val surface = classifySelectionSurface(
            accessibility = null,
            win32 = Win32SelectionSnapshot(
                className = "Chrome_Yandex_WidgetWin_1",
                hasCaret = false,
                isReadOnly = null
            ),
            clipboard = ClipboardSelectionEvidence(hasHtml = true, hasEditableMarkup = false)
        )

        assertEquals(SelectionSurface.READ_ONLY, surface)
        assertEquals(
            SelectionPresentation.AUTO_OVERLAY,
            resolveSelectionPresentation(surface, isAutoOverlayEnabled = true)
        )
    }

    private fun accessibility(
        controlType: Int,
        isEnabled: Boolean? = true,
        isValuePatternAvailable: Boolean? = false,
        isValueReadOnly: Boolean? = null,
        isTextEditPatternAvailable: Boolean? = false,
        isTextPatternAvailable: Boolean? = false,
        isKeyboardFocusable: Boolean? = null,
        hasKeyboardFocus: Boolean? = null
    ) = AccessibilitySelectionSnapshot(
        controlType = controlType,
        isEnabled = isEnabled,
        isValuePatternAvailable = isValuePatternAvailable,
        isValueReadOnly = isValueReadOnly,
        isTextEditPatternAvailable = isTextEditPatternAvailable,
        isTextPatternAvailable = isTextPatternAvailable,
        isKeyboardFocusable = isKeyboardFocusable,
        hasKeyboardFocus = hasKeyboardFocus
    )
}
