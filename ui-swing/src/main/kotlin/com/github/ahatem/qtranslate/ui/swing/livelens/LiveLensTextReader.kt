package com.github.ahatem.qtranslate.ui.swing.livelens

import com.sun.jna.Pointer
import com.sun.jna.Native
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
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.abs

internal data class LiveLensTextBlock(
    val text: String,
    val anchor: Point,
    val speaker: String? = null
)

internal data class LiveLensRawSample(
    val text: String,
    val anchor: Point,
    val controlType: Int,
    val elementBounds: Rectangle? = null
)

internal fun interface LiveLensTextReader {
    fun read(bounds: Rectangle): List<LiveLensTextBlock>
}

/**
 * Читает только текстовые accessibility-элементы под LIVE-рамкой.
 *
 * Координата сохраняется вместе с текстом, чтобы перевод можно было показать возле
 * исходного сообщения. Контейнеры Chromium, заголовки окон и кнопки исключаются по
 * UI Automation ControlType, поэтому они не отправляются переводчику.
 */
internal class WindowsAccessibilityLiveLensTextReader : LiveLensTextReader {
    override fun read(bounds: Rectangle): List<LiveLensTextBlock> {
        if (!isWindows() || bounds.width < 20 || bounds.height < 20) return emptyList()

        val initializeResult = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
        val initializedHere = initializeResult.toInt() >= 0
        if (!initializedHere && initializeResult.toInt() != RPC_E_CHANGED_MODE) return emptyList()

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
            if (createResult.failed() || automationPointer.isNullPointer()) return emptyList()

            val automation = AutomationClient(automationPointer)
            return try {
                val descendants = automation.readDescendants(bounds)
                val rawSamples = descendants.ifEmpty {
                    samplePoints(bounds).mapNotNull { point ->
                        automation.readElementAt(point.x, point.y, bounds)?.let { element ->
                            LiveLensRawSample(
                                text = element.name,
                                anchor = Point(point.x, point.y),
                                controlType = element.controlType,
                                elementBounds = element.bounds
                            )
                        }
                    }
                }
                normalizeLiveTextCandidates(rawSamples)
            } finally {
                automation.Release()
            }
        } finally {
            if (initializedHere) Ole32.INSTANCE.CoUninitialize()
        }
    }

    private fun samplePoints(bounds: Rectangle): List<WinDef.POINT.ByValue> {
        val horizontalInset = (bounds.width * 0.025).toInt().coerceAtLeast(8)
        val left = bounds.x + horizontalInset
        val right = bounds.x + bounds.width - horizontalInset
        val columns = listOf(0.06, 0.16, 0.27, 0.40, 0.56, 0.73, 0.90).map { ratio ->
            (left + (right - left) * ratio).toInt()
        }
        val step = (bounds.height / 55).coerceIn(14, 20)
        return buildList {
            var y = bounds.y + 10
            while (y < bounds.y + bounds.height - 8 && size + columns.size <= MAX_SAMPLE_POINTS) {
                columns.forEach { x -> add(WinDef.POINT.ByValue(x, y)) }
                y += step
            }
        }
    }

    private data class ElementSnapshot(
        val name: String,
        val controlType: Int,
        val bounds: Rectangle?
    )

    private class AutomationClient(pointer: Pointer) : Unknown(pointer) {
        fun readDescendants(bounds: Rectangle): List<LiveLensRawSample> {
            val rootHandle = findRootWindow(bounds) ?: return emptyList()
            val rootReference = PointerByReference()
            val rootResult = _invokeNativeObject(
                I_UI_AUTOMATION_ELEMENT_FROM_HANDLE_VTABLE_INDEX,
                arrayOf(pointer, rootHandle, rootReference),
                WinNT.HRESULT::class.java
            ) as WinNT.HRESULT
            if (rootResult.failed() || rootReference.value.isNullPointer()) return emptyList()

            val conditionReference = PointerByReference()
            val conditionResult = _invokeNativeObject(
                I_UI_AUTOMATION_CREATE_TRUE_CONDITION_VTABLE_INDEX,
                arrayOf(pointer, conditionReference),
                WinNT.HRESULT::class.java
            ) as WinNT.HRESULT
            if (conditionResult.failed() || conditionReference.value.isNullPointer()) {
                AutomationElement(rootReference.value).Release()
                return emptyList()
            }

            val root = AutomationElement(rootReference.value)
            val condition = AutomationCondition(conditionReference.value)
            return try {
                val elements = root.findAll(condition) ?: return emptyList()
                try {
                    buildList {
                        val count = elements.length().coerceAtMost(MAX_DESCENDANT_ELEMENTS)
                        val deadline = System.nanoTime() + SCAN_BUDGET_MS * NANOS_PER_MILLISECOND
                        repeat(count) { index ->
                            if (System.nanoTime() > deadline) return@buildList
                            val element = elements.elementAt(index) ?: return@repeat
                            try {
                                val snapshot = element.textSnapshot(bounds) ?: return@repeat
                                val elementBounds = snapshot.bounds ?: return@repeat
                                add(
                                    LiveLensRawSample(
                                        text = snapshot.name,
                                        anchor = elementBounds.textAnchor(),
                                        controlType = snapshot.controlType,
                                        elementBounds = elementBounds
                                    )
                                )
                            } finally {
                                element.Release()
                            }
                        }
                    }
                } finally {
                    elements.Release()
                }
            } finally {
                condition.Release()
                root.Release()
            }
        }

        fun readElementAt(x: Int, y: Int, visibleArea: Rectangle): ElementSnapshot? {
            val elementReference = PointerByReference()
            val result = _invokeNativeObject(
                I_UI_AUTOMATION_ELEMENT_FROM_POINT_VTABLE_INDEX,
                arrayOf(pointer, WinDef.POINT.ByValue(x, y), elementReference),
                WinNT.HRESULT::class.java
            ) as WinNT.HRESULT
            val elementPointer = elementReference.value
            if (result.failed() || elementPointer.isNullPointer()) return null

            val element = AutomationElement(elementPointer)
            return try {
                // Тот же фильтр контейнеров, что и в основном обходе: точка внутри рамки не
                // гарантирует, что весь найденный элемент — например, вся прокручиваемая
                // область чата — лежит внутри неё.
                element.textSnapshot(visibleArea)
            } finally {
                element.Release()
            }
        }

        private fun findRootWindow(bounds: Rectangle): WinDef.HWND? {
            val point = WinDef.POINT.ByValue(
                bounds.x + bounds.width / 2,
                bounds.y + bounds.height / 2
            )
            val window = WindowLookup.INSTANCE.WindowFromPoint(point)
            val root = window?.let { User32.INSTANCE.GetAncestor(it, GA_ROOT) ?: it }
            if (root != null && !isCamWorkWindow(root)) return root

            var candidate = root?.let {
                User32.INSTANCE.GetWindow(it, WinDef.DWORD(GW_HWNDNEXT.toLong()))
            }
            while (candidate != null) {
                if (
                    User32.INSTANCE.IsWindowVisible(candidate) &&
                    !isCamWorkWindow(candidate) &&
                    containsPoint(candidate, point)
                ) {
                    return candidate
                }
                candidate = User32.INSTANCE.GetWindow(candidate, WinDef.DWORD(GW_HWNDNEXT.toLong()))
            }

            var enumerated: WinDef.HWND? = null
            User32.INSTANCE.EnumWindows(
                WinUser.WNDENUMPROC { handle, _ ->
                    if (
                        User32.INSTANCE.IsWindowVisible(handle) &&
                        !isCamWorkWindow(handle) &&
                        containsPoint(handle, point)
                    ) {
                        enumerated = handle
                        false
                    } else {
                        true
                    }
                },
                null
            )
            return enumerated
        }

        private fun isCamWorkWindow(window: WinDef.HWND): Boolean {
            val processId = IntByReference()
            User32.INSTANCE.GetWindowThreadProcessId(window, processId)
            val pid = processId.value.toLong()
            if (pid == ProcessHandle.current().pid()) return true

            return runCatching {
                ProcessHandle.of(pid)
                    .map { process ->
                        process.info().commandLine().orElse("").lowercase().let { commandLine ->
                            commandLine.contains("camwork translate") ||
                                commandLine.contains("camwork-translate") ||
                                commandLine.contains("app-all.jar")
                        }
                    }
                    .orElse(false)
            }.getOrDefault(false)
        }

        private fun containsPoint(window: WinDef.HWND, point: WinDef.POINT.ByValue): Boolean {
            val rectangle = WinDef.RECT()
            if (!User32.INSTANCE.GetWindowRect(window, rectangle)) return false
            return point.x in rectangle.left until rectangle.right &&
                point.y in rectangle.top until rectangle.bottom
        }
    }

    private class AutomationElement(pointer: Pointer) : Unknown(pointer) {
        /**
         * Каждое свойство UI Automation — отдельный межпроцессный вызов, поэтому порядок важен:
         * сначала дешёвый ControlType отсекает контейнеры Chromium, и только выжившие элементы
         * стоят чтения прямоугольника и имени.
         */
        fun textSnapshot(visibleArea: Rectangle): ElementSnapshot? {
            val controlType = intProperty(UIA_CONTROL_TYPE_PROPERTY) ?: return null
            if (controlType !in SUPPORTED_TEXT_CONTROL_TYPES) return null
            val elementBounds = currentBounds() ?: return null
            if (!isMostlyInsideScanArea(elementBounds, visibleArea)) return null
            val name = stringProperty(UIA_NAME_PROPERTY)?.trim().orEmpty()
            if (name.isBlank()) return null
            return ElementSnapshot(name, controlType, elementBounds)
        }

        fun snapshot(): ElementSnapshot? {
            val name = stringProperty(UIA_NAME_PROPERTY)?.trim().orEmpty()
            val controlType = intProperty(UIA_CONTROL_TYPE_PROPERTY) ?: return null
            if (name.isBlank()) return null
            return ElementSnapshot(name, controlType, currentBounds())
        }

        fun findAll(condition: AutomationCondition): AutomationElementArray? {
            val arrayReference = PointerByReference()
            val result = _invokeNativeObject(
                I_UI_AUTOMATION_ELEMENT_FIND_ALL_VTABLE_INDEX,
                arrayOf(pointer, TREE_SCOPE_DESCENDANTS, condition.pointer, arrayReference),
                WinNT.HRESULT::class.java
            ) as WinNT.HRESULT
            return if (result.failed() || arrayReference.value.isNullPointer()) {
                null
            } else {
                AutomationElementArray(arrayReference.value)
            }
        }

        private fun currentBounds(): Rectangle? {
            val rectangle = WinDef.RECT()
            val result = _invokeNativeObject(
                I_UI_AUTOMATION_ELEMENT_GET_CURRENT_BOUNDING_RECTANGLE_VTABLE_INDEX,
                arrayOf(pointer, rectangle),
                WinNT.HRESULT::class.java
            ) as WinNT.HRESULT
            if (result.failed()) return null
            rectangle.read()
            val width = rectangle.right - rectangle.left
            val height = rectangle.bottom - rectangle.top
            return if (width > 0 && height > 0) {
                Rectangle(rectangle.left, rectangle.top, width, height)
            } else {
                null
            }
        }

        fun stringProperty(propertyId: Int): String? = property(propertyId) { value ->
            if (value.getVarType().toInt() == Variant.VT_BSTR) value.stringValue() else null
        }

        fun intProperty(propertyId: Int): Int? = property(propertyId) { value ->
            when (value.getVarType().toInt()) {
                Variant.VT_I4,
                Variant.VT_INT -> value.intValue()
                else -> null
            }
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

    private class AutomationCondition(pointer: Pointer) : Unknown(pointer)

    private class AutomationElementArray(pointer: Pointer) : Unknown(pointer) {
        fun length(): Int {
            val resultValue = IntByReference()
            val result = _invokeNativeObject(
                I_UI_AUTOMATION_ELEMENT_ARRAY_GET_LENGTH_VTABLE_INDEX,
                arrayOf(pointer, resultValue),
                WinNT.HRESULT::class.java
            ) as WinNT.HRESULT
            return if (result.failed()) 0 else resultValue.value.coerceAtLeast(0)
        }

        fun elementAt(index: Int): AutomationElement? {
            val elementReference = PointerByReference()
            val result = _invokeNativeObject(
                I_UI_AUTOMATION_ELEMENT_ARRAY_GET_ELEMENT_VTABLE_INDEX,
                arrayOf(pointer, index, elementReference),
                WinNT.HRESULT::class.java
            ) as WinNT.HRESULT
            return if (result.failed() || elementReference.value.isNullPointer()) {
                null
            } else {
                AutomationElement(elementReference.value)
            }
        }
    }

    private interface WindowLookup : StdCallLibrary {
        fun WindowFromPoint(point: WinDef.POINT.ByValue): WinDef.HWND?

        companion object {
            val INSTANCE: WindowLookup = Native.load("user32", WindowLookup::class.java)
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private companion object {
        val C_UI_AUTOMATION = Guid.CLSID("{FF48DBA4-60EF-4201-AA87-54103EEF594E}")
        val I_UI_AUTOMATION = Guid.IID("{30CBE57D-D9D0-452A-AB13-7AC5AC4825EE}")

        const val I_UI_AUTOMATION_ELEMENT_FROM_POINT_VTABLE_INDEX = 7
        const val I_UI_AUTOMATION_ELEMENT_FROM_HANDLE_VTABLE_INDEX = 6
        const val I_UI_AUTOMATION_CREATE_TRUE_CONDITION_VTABLE_INDEX = 21
        const val I_UI_AUTOMATION_ELEMENT_FIND_ALL_VTABLE_INDEX = 6
        const val I_UI_AUTOMATION_ELEMENT_GET_CURRENT_PROPERTY_VALUE_VTABLE_INDEX = 10
        // Проверено на Windows 11: 37 — это get_CurrentItemType, прямоугольник лежит на 43.
        const val I_UI_AUTOMATION_ELEMENT_GET_CURRENT_BOUNDING_RECTANGLE_VTABLE_INDEX = 43
        const val I_UI_AUTOMATION_ELEMENT_ARRAY_GET_LENGTH_VTABLE_INDEX = 3
        const val I_UI_AUTOMATION_ELEMENT_ARRAY_GET_ELEMENT_VTABLE_INDEX = 4
        const val UIA_NAME_PROPERTY = 30_005
        const val UIA_CONTROL_TYPE_PROPERTY = 30_003
        const val RPC_E_CHANGED_MODE = -2_147_417_850
        const val MAX_SAMPLE_POINTS = 420
        const val MAX_DESCENDANT_ELEMENTS = 4_000
        const val SCAN_BUDGET_MS = 900L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val TREE_SCOPE_DESCENDANTS = 0x4
        const val GA_ROOT = 2
        const val GW_HWNDNEXT = 2
    }
}

internal fun normalizeLiveTextCandidates(raw: List<LiveLensRawSample>): List<LiveLensTextBlock> {
    val result = mutableListOf<LiveLensTextBlock>()
    raw.asSequence()
        .filter { it.controlType in SUPPORTED_TEXT_CONTROL_TYPES }
        .flatMap { sample ->
            sample.text.lineSequence().map { line -> sample.copy(text = line) }
        }
        .map { sample -> sample.copy(text = sample.text.replace(Regex("\\s+"), " ").trim()) }
        .filter { sample -> sample.text.length in 2..800 }
        .filter { sample -> sample.text.count(Char::isLetter) >= 2 }
        .filterNot { sample -> isSystemChromeText(sample.text) }
        .sortedBy { sample -> sample.anchor.y }
        .forEach { sample ->
            val normalizedAnchor = sample.elementBounds?.textAnchor() ?: sample.anchor
            val duplicateIndex = result.indexOfFirst { existing ->
                existing.text.equals(sample.text, ignoreCase = true) &&
                    isSameAccessibilityElement(existing, normalizedAnchor, sample.elementBounds != null)
            }
            if (duplicateIndex < 0) {
                result += LiveLensTextBlock(sample.text, Point(normalizedAnchor))
            } else {
                val existing = result[duplicateIndex]
                result[duplicateIndex] = existing.copy(
                    anchor = Point(
                        (existing.anchor.x + normalizedAnchor.x) / 2,
                        (existing.anchor.y + normalizedAnchor.y) / 2
                    )
                )
            }
        }
    return result
}

private fun isSameAccessibilityElement(
    existing: LiveLensTextBlock,
    candidateAnchor: Point,
    hasElementBounds: Boolean
): Boolean {
    if (hasElementBounds) {
        return existing.anchor == candidateAnchor
    }
    return abs(existing.anchor.y - candidateAnchor.y) <= SAME_ELEMENT_Y_TOLERANCE
}

/**
 * Требует, чтобы найденный элемент лежал внутри рамки почти целиком, а не просто задевал её
 * край одним пикселем.
 *
 * FindAll обходит весь верхнеуровневый документ окна, а не то, что физически видно под рамкой.
 * Без этой проверки прокручиваемый контейнер чата — высотой в тысячи пикселей и именем,
 * склеенным accessibility-деревом из текста всех сообщений, — проходил старую проверку
 * `intersects` по касанию края рамки и выливал в неё текст со всей страницы.
 */
internal fun isMostlyInsideScanArea(elementBounds: Rectangle, visibleArea: Rectangle): Boolean {
    val intersection = elementBounds.intersection(visibleArea)
    if (intersection.isEmpty) return false
    val elementArea = elementBounds.width.toDouble() * elementBounds.height
    if (elementArea <= 0) return false
    val intersectionArea = intersection.width.toDouble() * intersection.height
    return intersectionArea / elementArea >= MIN_SCAN_AREA_CONTAINMENT_RATIO
}

private const val MIN_SCAN_AREA_CONTAINMENT_RATIO = 0.6

private fun Rectangle.textAnchor(): Point = Point(
    x,
    y + minOf(height, MAX_TEXT_LINE_HEIGHT) / 2
)

private fun isSystemChromeText(text: String): Boolean {
    val normalized = text.trim()
    return normalized.endsWith(" - Google Chrome", ignoreCase = true) ||
        normalized.equals("Google Chrome", ignoreCase = true) ||
        normalized.equals("LIVE-перевод", ignoreCase = true) ||
        normalized.startsWith("CamWork Translate", ignoreCase = true)
}

private val SUPPORTED_TEXT_CONTROL_TYPES = setOf(
    50_005, // Hyperlink
    50_007, // ListItem
    50_020, // Text
    50_029  // DataItem
)

private const val SAME_ELEMENT_Y_TOLERANCE = 34
private const val MAX_TEXT_LINE_HEIGHT = 28

private fun WinNT.HRESULT.failed(): Boolean = toInt() < 0

private fun Pointer?.isNullPointer(): Boolean =
    this == null || Pointer.nativeValue(this) == 0L
