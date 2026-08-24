package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.datatransfer.Transferable
import java.io.Reader

/** Обезличенные признаки формата копирования без сохранения выделенного текста или HTML. */
internal data class ClipboardSelectionEvidence(
    val hasHtml: Boolean,
    val hasEditableMarkup: Boolean
)

/**
 * Chromium копирует опубликованный DOM как text/plain + text/html, а обычные input/textarea —
 * только как text/plain. Это ограниченный compatibility-fallback для браузеров, которые
 * принудительно отключают Windows accessibility (например, AdsPower). Явный editable-маркер
 * защищает поле ввода, если браузер сохранил его в HTML. Очищенный Chromium contenteditable
 * неотличим от опубликованного DOM без UI Automation или браузерного расширения.
 */
internal fun inspectClipboardSelectionEvidence(
    contents: Transferable?
): ClipboardSelectionEvidence? {
    contents ?: return null
    val htmlFlavor = contents.transferDataFlavors.firstOrNull { flavor ->
        runCatching { flavor.isMimeTypeEqual("text/html") }.getOrDefault(false)
    } ?: return ClipboardSelectionEvidence(hasHtml = false, hasEditableMarkup = false)

    val markupPrefix = runCatching {
        htmlFlavor.getReaderForText(contents).use { reader ->
            reader.readAtMost(MAX_HTML_INSPECTION_CHARS)
        }
    }.getOrNull().orEmpty()

    return ClipboardSelectionEvidence(
        hasHtml = true,
        hasEditableMarkup = EDITABLE_HTML_MARKER.containsMatchIn(markupPrefix)
    )
}

private fun Reader.readAtMost(limit: Int): String {
    val result = StringBuilder(limit.coerceAtMost(4_096))
    val buffer = CharArray(2_048)
    while (result.length < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - result.length))
        if (count <= 0) break
        result.append(buffer, 0, count)
    }
    return result.toString()
}

private const val MAX_HTML_INSPECTION_CHARS = 32_768

private val EDITABLE_HTML_MARKER = Regex(
    pattern = """(?is)(?:\bcontenteditable\s*=\s*["']?(?:true|plaintext-only)\b|\brole\s*=\s*["']textbox\b|<\s*(?:input|textarea)\b)"""
)
