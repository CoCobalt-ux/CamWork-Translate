package com.github.ahatem.qtranslate.ui.swing.livelens

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.awt.Point
import java.awt.Rectangle

/**
 * Читает текстовые accessibility-элементы фокусного окна через AXUIElement — macOS-эквивалент
 * [WindowsAccessibilityLiveLensTextReader].
 *
 * ### Почему не отдельный помощник, а прямой JNA-вызов в этом же процессе
 * `docs/MACOS_SELECTION.md` рекомендует для нового accessibility-кода отдельный Swift/ObjC
 * бинарник — там проще типы и не нужна ручная работа с CoreFoundation. Для чтения дерева это,
 * однако, означало бы, что macOS покажет системный запрос «Универсальный доступ» ОТДЕЛЬНО для
 * этого бинарника, а не только для самого приложения: TCC исторически считает независимый
 * исполняемый файл внутри `Contents/MacOS` самостоятельным клиентом разрешения. Пользователь
 * получил бы два запроса вместо одного, что прямо противоречит цели «так же гладко, как DeepL».
 * Vision OCR ([MacVisionOcrService]) от этого не страдает — распознавание готового файла
 * изображения разрешения не требует вовсе, поэтому там вынесение в помощник безопасно и сделано.
 *
 * ### Ограничение текущей реализации
 * Читается только окно приложения, у которого сейчас системный фокус (`AXFocusedApplication` →
 * `AXFocusedWindow`), а не произвольное окно под рамкой — в отличие от Windows, где
 * `WindowFromPoint`/`EnumWindows` находят окно по экранной точке независимо от фокуса. Для
 * обычного сценария (пользователь только что кликнул в чат, чтобы позиционировать рамку) это
 * совпадает с ожидаемым поведением; поддержка произвольного окна под рамкой потребует
 * `CGWindowListCopyWindowInfo` и разбор `CFDictionary`/`CFNumber`, что не добавлено — этот
 * класс кода не проверен на живом Mac, и лишний CoreFoundation-код без возможности его
 * протестировать увеличивает риск больше, чем даёт пользы. Оставлено на следующий проход
 * с реальным устройством под рукой.
 *
 * ### Управление памятью CoreFoundation
 * Только результаты вызовов `...Copy...` (задокументированная передача владения) освобождаются
 * через `CFRelease`; значения, полученные из `CFArrayGetValueAtIndex` (заимствование), — нет.
 * Каждый уровень обхода освобождает свой массив только после того, как использование его
 * элементов, включая рекурсию в их потомков, полностью завершено.
 */
internal class MacAccessibilityLiveLensTextReader : LiveLensTextReader {
    override fun read(bounds: Rectangle): List<LiveLensTextBlock> {
        if (!isMacOs() || bounds.width < 20 || bounds.height < 20) return emptyList()
        if (!isAccessibilityTrusted()) return emptyList()

        val systemWide = AX.INSTANCE.AXUIElementCreateSystemWide() ?: return emptyList()
        try {
            val focusedApplication = copyElementAttribute(systemWide, Attribute.FOCUSED_APPLICATION)
                ?: return emptyList()
            try {
                val focusedWindow = copyElementAttribute(focusedApplication, Attribute.FOCUSED_WINDOW)
                    ?: return emptyList()
                try {
                    val samples = mutableListOf<LiveLensRawSample>()
                    val budget = Budget()
                    walk(focusedWindow, bounds, samples, budget, depth = 0)
                    return normalizeLiveTextCandidates(samples)
                } finally {
                    CF.INSTANCE.CFRelease(focusedWindow)
                }
            } finally {
                CF.INSTANCE.CFRelease(focusedApplication)
            }
        } finally {
            CF.INSTANCE.CFRelease(systemWide)
        }
    }

    private class Budget {
        var visited = 0
        val deadlineNanos = System.nanoTime() + SCAN_BUDGET_MS * NANOS_PER_MILLISECOND
        fun exhausted(): Boolean = visited >= MAX_ELEMENTS || System.nanoTime() > deadlineNanos
    }

    private fun walk(
        element: Pointer,
        scanArea: Rectangle,
        out: MutableList<LiveLensRawSample>,
        budget: Budget,
        depth: Int
    ) {
        if (depth > MAX_DEPTH || budget.exhausted()) return
        budget.visited++

        val role = stringAttribute(element, Attribute.ROLE)
        if (role != null) {
            val controlType = TEXT_ROLE_CONTROL_TYPES[role]
            if (controlType != null) {
                val elementBounds = boundsOf(element)
                if (elementBounds != null && isMostlyInsideScanArea(elementBounds, scanArea)) {
                    val text = stringAttribute(element, Attribute.VALUE)
                        ?: stringAttribute(element, Attribute.TITLE)
                        ?: stringAttribute(element, Attribute.DESCRIPTION)
                    if (!text.isNullOrBlank()) {
                        out += LiveLensRawSample(
                            text = text,
                            anchor = elementBounds.textAnchor(),
                            controlType = controlType,
                            elementBounds = elementBounds
                        )
                    }
                }
            }
        }

        val children = copyElementAttribute(element, Attribute.CHILDREN) ?: return
        try {
            if (CF.INSTANCE.CFGetTypeID(children) != CF.INSTANCE.CFArrayGetTypeID()) return
            val count = CF.INSTANCE.CFArrayGetCount(children).toLong()
            var index = 0L
            while (index < count && !budget.exhausted()) {
                val child = CF.INSTANCE.CFArrayGetValueAtIndex(children, NativeLong(index))
                if (child != null) walk(child, scanArea, out, budget, depth + 1)
                index++
            }
        } finally {
            CF.INSTANCE.CFRelease(children)
        }
    }

    /** `AXUIElementCopyAttributeValue` даёт значение с передачей владения — вызывающий обязан освободить его. */
    private fun copyElementAttribute(element: Pointer, attribute: Attribute): Pointer? {
        val out = PointerByReference()
        val error = AX.INSTANCE.AXUIElementCopyAttributeValue(element, attribute.cfString, out)
        return out.value.takeIf { error == AX_ERROR_SUCCESS }
    }

    private fun stringAttribute(element: Pointer, attribute: Attribute): String? {
        val value = copyElementAttribute(element, attribute) ?: return null
        try {
            if (CF.INSTANCE.CFGetTypeID(value) != CF.INSTANCE.CFStringGetTypeID()) return null
            return cfStringToJava(value)?.trim()?.takeIf { it.isNotBlank() }
        } finally {
            CF.INSTANCE.CFRelease(value)
        }
    }

    private fun boundsOf(element: Pointer): Rectangle? {
        val position = readAxValueStruct(element, Attribute.POSITION, AX_VALUE_CG_POINT_TYPE) ?: return null
        val size = readAxValueStruct(element, Attribute.SIZE, AX_VALUE_CG_SIZE_TYPE) ?: return null
        val width = size.first.toInt()
        val height = size.second.toInt()
        if (width <= 0 || height <= 0) return null
        return Rectangle(position.first.toInt(), position.second.toInt(), width, height)
    }

    /** `AXPosition`/`AXSize` — не сам CGPoint/CGSize, а AXValueRef-обёртка вокруг него. */
    private fun readAxValueStruct(
        element: Pointer,
        attribute: Attribute,
        axValueType: Int
    ): Pair<Double, Double>? {
        val axValue = copyElementAttribute(element, attribute) ?: return null
        try {
            val buffer = Memory(16) // CGFloat — 8 байт на 64-битной macOS; CGPoint/CGSize — два поля.
            val ok = AX.INSTANCE.AXValueGetValue(axValue, axValueType, buffer) != ZERO_BYTE
            if (!ok) return null
            return buffer.getDouble(0) to buffer.getDouble(8)
        } finally {
            CF.INSTANCE.CFRelease(axValue)
        }
    }

    private fun cfStringToJava(value: Pointer): String? {
        val length = CF.INSTANCE.CFStringGetLength(value)
        val maxSize = CF.INSTANCE.CFStringGetMaximumSizeForEncoding(length, K_CF_STRING_ENCODING_UTF8)
        val bufferSize = maxSize.toLong() + 1
        if (bufferSize <= 1 || bufferSize > MAX_STRING_BUFFER_BYTES) return null
        val buffer = Memory(bufferSize)
        val ok = CF.INSTANCE.CFStringGetCString(
            value,
            buffer,
            NativeLong(bufferSize),
            K_CF_STRING_ENCODING_UTF8
        ) != ZERO_BYTE
        return if (ok) buffer.getString(0, "UTF-8") else null
    }

    private fun isAccessibilityTrusted(): Boolean =
        runCatching { MacAxTrust.INSTANCE.AXIsProcessTrusted() != ZERO_BYTE }.getOrDefault(false)

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    private enum class Attribute(name: String) {
        FOCUSED_APPLICATION("AXFocusedApplication"),
        FOCUSED_WINDOW("AXFocusedWindow"),
        CHILDREN("AXChildren"),
        ROLE("AXRole"),
        VALUE("AXValue"),
        TITLE("AXTitle"),
        DESCRIPTION("AXDescription"),
        POSITION("AXPosition"),
        SIZE("AXSize");

        // Константы времени жизни процесса: создаются один раз и намеренно не освобождаются,
        // как и любые interned-строки — их конечное количество не растёт с числом опросов рамки.
        val cfString: Pointer by lazy {
            requireNotNull(CF.INSTANCE.CFStringCreateWithCString(null, name, K_CF_STRING_ENCODING_UTF8)) {
                "CFStringCreateWithCString вернул null для $name"
            }
        }
    }

    private companion object {
        const val MAX_ELEMENTS = 800
        const val MAX_DEPTH = 40
        const val SCAN_BUDGET_MS = 600L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_STRING_BUFFER_BYTES = 4_096L
        const val ZERO_BYTE: Byte = 0
        const val AX_ERROR_SUCCESS = 0
        const val AX_VALUE_CG_POINT_TYPE = 1
        const val AX_VALUE_CG_SIZE_TYPE = 2
        const val K_CF_STRING_ENCODING_UTF8 = 0x08000100

        /**
         * Те же числовые ControlType, что и в [SUPPORTED_TEXT_CONTROL_TYPES] на Windows —
         * значение не имеет собственного смысла здесь, только совпадение с фильтром внутри
         * общего [normalizeLiveTextCandidates], который остаётся одним куском кода для обеих
         * платформ.
         */
        val TEXT_ROLE_CONTROL_TYPES: Map<String, Int> = mapOf(
            "AXStaticText" to 50_020, // как Windows Text
            "AXLink" to 50_005, // как Windows Hyperlink
            "AXCell" to 50_029 // как Windows DataItem
        )
    }
}

private interface CF : Library {
    fun CFStringCreateWithCString(alloc: Pointer?, cStr: String, encoding: Int): Pointer?
    fun CFRelease(cf: Pointer?)
    fun CFGetTypeID(cf: Pointer?): NativeLong
    fun CFStringGetTypeID(): NativeLong
    fun CFArrayGetTypeID(): NativeLong
    fun CFArrayGetCount(array: Pointer?): NativeLong
    fun CFArrayGetValueAtIndex(array: Pointer?, index: NativeLong): Pointer?
    fun CFStringGetLength(str: Pointer?): NativeLong
    fun CFStringGetMaximumSizeForEncoding(length: NativeLong, encoding: Int): NativeLong
    fun CFStringGetCString(str: Pointer?, buffer: Pointer, bufferSize: NativeLong, encoding: Int): Byte

    companion object {
        val INSTANCE: CF by lazy { Native.load("CoreFoundation", CF::class.java) }
    }
}

/** Отдельный минимальный интерфейс для AX-функций — [MacAccessibilityPermission] грузит ту же
 *  библиотеку своим интерфейсом только с `AXIsProcessTrusted`; здесь нужен более широкий набор. */
private interface AX : Library {
    fun AXUIElementCreateSystemWide(): Pointer?
    fun AXUIElementCopyAttributeValue(element: Pointer, attribute: Pointer, value: PointerByReference): Int
    fun AXValueGetValue(value: Pointer, type: Int, valuePtr: Pointer): Byte

    companion object {
        val INSTANCE: AX by lazy { Native.load("ApplicationServices", AX::class.java) }
    }
}

private interface MacAxTrust : Library {
    @Suppress("FunctionName")
    fun AXIsProcessTrusted(): Byte

    companion object {
        val INSTANCE: MacAxTrust by lazy { Native.load("ApplicationServices", MacAxTrust::class.java) }
    }
}

private fun Rectangle.textAnchor(): Point = Point(x, y + minOf(height, 28) / 2)
