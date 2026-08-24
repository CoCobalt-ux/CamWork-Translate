package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.Point

/** Тип поверхности, на которой пользователь выделил текст во внешнем приложении. */
internal enum class SelectionSurface {
    EDITABLE,
    READ_ONLY,
    UNKNOWN
}

/** Нормализованный снимок свойств Windows UI Automation. */
internal data class AccessibilitySelectionSnapshot(
    val controlType: Int?,
    val isEnabled: Boolean?,
    val isValuePatternAvailable: Boolean?,
    val isValueReadOnly: Boolean?,
    val isTextEditPatternAvailable: Boolean?,
    val isTextPatternAvailable: Boolean? = null,
    val isKeyboardFocusable: Boolean? = null,
    val hasKeyboardFocus: Boolean? = null
)

/** Нормализованный fallback-снимок активного Win32 control. */
internal data class Win32SelectionSnapshot(
    val className: String,
    val hasCaret: Boolean,
    val isReadOnly: Boolean?,
    val isCaretNearSelection: Boolean = hasCaret,
    /** Точка выделения действительно лежит внутри сфокусированного Win32 control. */
    val isSelectionInsideControl: Boolean = true
)

/** Полный обезличенный результат системного определения поверхности для диагностики. */
internal data class SelectionSurfaceDetection(
    val surface: SelectionSurface,
    val accessibility: AccessibilitySelectionSnapshot?,
    val win32: Win32SelectionSnapshot?,
    val clipboard: ClipboardSelectionEvidence? = null,
    val accessibilityError: String? = null,
    val win32Error: String? = null
)

/**
 * Чистая классификация без обращения к ОС. UI Automation имеет приоритет, потому что она
 * различает Edit/Text/Document и Value.IsReadOnly даже в Chromium, Electron, Qt и WinUI.
 */
internal fun classifySelectionSurface(
    accessibility: AccessibilitySelectionSnapshot?,
    win32: Win32SelectionSnapshot?,
    clipboard: ClipboardSelectionEvidence? = null
): SelectionSurface {
    val accessibilityResult = classifyAccessibility(accessibility)
    val win32Result = classifyWin32(win32)
    val clipboardResult = classifyClipboard(clipboard, win32)

    // UI Automation даёт семантику элемента под точкой, поэтому её явный результат имеет
    // приоритет над общим Win32 caret. Chromium создаёт caret и для обычного выделения текста
    // на странице; считать его полем ввода означало всегда показывать mini-button.
    if (accessibilityResult == SelectionSurface.EDITABLE) {
        return SelectionSurface.EDITABLE
    }

    // Настоящий нативный Edit/RichEdit остаётся сильным признаком редактирования даже тогда,
    // когда UIA вернула дочерний Text. В отличие от caret, класс control однозначен.
    if (isExplicitEditableWin32Control(win32)) {
        return SelectionSurface.EDITABLE
    }

    // HTML clipboard используется только когда UIA не дала ответа. Он сильнее общего Chromium
    // caret: опубликованный DOM также может оставлять caret, хотя не является полем ввода.
    return accessibilityResult ?: clipboardResult ?: win32Result ?: SelectionSurface.UNKNOWN
}

/**
 * Консервативная классификация для macOS/Linux, где Win32 UI Automation недоступна.
 *
 * Браузеры сохраняют опубликованный DOM как HTML, тогда как обычные поля ввода чаще всего
 * отдают только plain text. Неизвестный формат намеренно не считается опубликованным текстом:
 * в таком случае пользователь получает безопасную мини-кнопку вместо неожиданного popup.
 */
internal fun classifyPortableSelectionSurface(
    clipboard: ClipboardSelectionEvidence?
): SelectionSurface = when {
    clipboard?.hasEditableMarkup == true -> SelectionSurface.EDITABLE
    clipboard?.hasHtml == true -> SelectionSurface.READ_ONLY
    else -> SelectionSurface.UNKNOWN
}

private fun classifyClipboard(
    snapshot: ClipboardSelectionEvidence?,
    win32: Win32SelectionSnapshot?
): SelectionSurface? = when {
    snapshot == null -> null
    snapshot.hasEditableMarkup -> SelectionSurface.EDITABLE
    snapshot.hasHtml && isChromiumContainer(win32) -> SelectionSurface.READ_ONLY
    else -> null
}

private fun isChromiumContainer(snapshot: Win32SelectionSnapshot?): Boolean {
    val className = snapshot?.className?.lowercase().orEmpty()
    return CHROMIUM_WIN32_CLASS_MARKERS.any(className::contains)
}

private fun classifyAccessibility(snapshot: AccessibilitySelectionSnapshot?): SelectionSurface? {
    snapshot ?: return null

    if (snapshot.isEnabled == false) return SelectionSurface.READ_ONLY
    if (snapshot.isTextEditPatternAvailable == true) return SelectionSurface.EDITABLE

    if (snapshot.isValuePatternAvailable == true) {
        when (snapshot.isValueReadOnly) {
            false -> return SelectionSurface.EDITABLE
            true -> return SelectionSurface.READ_ONLY
            null -> Unit
        }
    }

    return when (snapshot.controlType) {
        UIA_EDIT_CONTROL_TYPE,
        UIA_COMBO_BOX_CONTROL_TYPE,
        UIA_SPINNER_CONTROL_TYPE -> SelectionSurface.EDITABLE

        // UIA Text сам по себе не является полем ввода. В Chromium фокус часто остаётся на
        // корне документа или дочернем Text, хотя пользователь выделил опубликованное сообщение.
        // Реальный contenteditable выше уже распознаётся по TextEdit/Value/Edit или Win32 caret.
        UIA_TEXT_CONTROL_TYPE -> SelectionSurface.READ_ONLY
        UIA_HYPERLINK_CONTROL_TYPE -> SelectionSurface.READ_ONLY

        // Document и Custom+TextPattern — обычное представление опубликованного текста в
        // Chromium. KeyboardFocusable/HasKeyboardFocus у корня страницы не означает, что
        // выделение находится в поле ввода; положительные edit-признаки уже обработаны выше.
        UIA_DOCUMENT_CONTROL_TYPE -> classifyAmbiguousTextPattern(snapshot)
        else -> classifyAmbiguousTextPattern(snapshot)
    }
}

private fun classifyAmbiguousTextPattern(
    snapshot: AccessibilitySelectionSnapshot
): SelectionSurface? = when {
    snapshot.isTextPatternAvailable != true -> null
    else -> SelectionSurface.READ_ONLY
}

private fun classifyWin32(snapshot: Win32SelectionSnapshot?): SelectionSurface? {
    snapshot ?: return null
    val className = snapshot.className.lowercase()
    val isEditControl = EDITABLE_WIN32_CLASS_MARKERS.any(className::contains)

    if (isEditControl && snapshot.isSelectionInsideControl) {
        return if (snapshot.isReadOnly == true) {
            SelectionSurface.READ_ONLY
        } else {
            SelectionSurface.EDITABLE
        }
    }
    // У активного чата caret часто остаётся в поле ввода, пока мышью выделяют уже
    // опубликованное сообщение. Такой удалённый caret не описывает поверхность выделения.
    if (snapshot.hasCaret && snapshot.isCaretNearSelection) return SelectionSurface.EDITABLE
    if (READ_ONLY_WIN32_CLASS_MARKERS.any(className::contains)) return SelectionSurface.READ_ONLY
    return null
}

private fun isExplicitEditableWin32Control(snapshot: Win32SelectionSnapshot?): Boolean {
    snapshot ?: return false
    val className = snapshot.className.lowercase()
    return snapshot.isSelectionInsideControl &&
        snapshot.isReadOnly != true &&
        EDITABLE_WIN32_CLASS_MARKERS.any(className::contains)
}

/** Взаимоисключающий результат единственного безопасного clipboard-capture. */
internal enum class SelectionPresentation {
    MINI_BUTTON,
    AUTO_OVERLAY
}

/**
 * В поле ввода popup никогда не появляется сам. UNKNOWN остаётся безопасным отказом с
 * mini-button: если accessibility сломана или отключена, нельзя надёжно отличить форму ввода от
 * опубликованного текста. Chromium штатно определяется как READ_ONLY через UI Automation.
 */
internal fun resolveSelectionPresentation(
    surface: SelectionSurface,
    isAutoOverlayEnabled: Boolean
): SelectionPresentation = when (surface) {
    SelectionSurface.READ_ONLY -> if (isAutoOverlayEnabled) {
        SelectionPresentation.AUTO_OVERLAY
    } else {
        SelectionPresentation.MINI_BUTTON
    }

    SelectionSurface.EDITABLE,
    SelectionSurface.UNKNOWN -> SelectionPresentation.MINI_BUTTON
}

/**
 * JNativeHook сообщает Windows desktop pixels, тогда как Swing использует AWT user space.
 * Снимок MouseInfo сделан в тот же момент события и уже учитывает DPI конкретного монитора.
 */
internal fun resolveAwtScreenPoint(nativePoint: Point, awtPointerSnapshot: Point?): Point =
    Point(awtPointerSnapshot ?: nativePoint)

/** Маршрутизация результата capture вынесена в чистую функцию для проверки auto/click веток. */
internal fun deliverSelectionPresentation(
    text: String,
    pointer: Point,
    presentation: SelectionPresentation,
    onMiniButton: (String, Point) -> Unit,
    onAutoOverlay: (String) -> Unit
) {
    if (text.isBlank()) return

    when (presentation) {
        SelectionPresentation.MINI_BUTTON -> onMiniButton(text, pointer)
        SelectionPresentation.AUTO_OVERLAY -> onAutoOverlay(text)
    }
}

private const val UIA_COMBO_BOX_CONTROL_TYPE = 50_003
private const val UIA_EDIT_CONTROL_TYPE = 50_004
private const val UIA_HYPERLINK_CONTROL_TYPE = 50_005
private const val UIA_SPINNER_CONTROL_TYPE = 50_016
private const val UIA_TEXT_CONTROL_TYPE = 50_020
private const val UIA_DOCUMENT_CONTROL_TYPE = 50_030

private val EDITABLE_WIN32_CLASS_MARKERS = listOf(
    "edit",
    "richedit",
    "scintilla",
    "textbox",
    "tmemo"
)

private val READ_ONLY_WIN32_CLASS_MARKERS = listOf("static", "syslink")

private val CHROMIUM_WIN32_CLASS_MARKERS = listOf(
    "chrome_widgetwin",
    "chrome_renderwidgethosthwnd"
)
