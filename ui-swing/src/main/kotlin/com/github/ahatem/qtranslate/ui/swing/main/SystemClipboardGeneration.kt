package com.github.ahatem.qtranslate.ui.swing.main

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary

/**
 * Номер изменения системного clipboard в Windows.
 *
 * Сравнения текста недостаточно: пользователь может самостоятельно скопировать тот же текст,
 * пока CamWork временно владеет clipboard. Sequence number отличает это внешнее копирование и
 * запрещает позднее восстановление устаревшего содержимого.
 */
internal fun currentSystemClipboardGeneration(): Long? {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return null

    return runCatching {
        ClipboardSequenceUser32.INSTANCE.GetClipboardSequenceNumber().toLong() and UNSIGNED_INT_MASK
    }.getOrNull()
}

internal fun ownsTemporaryClipboard(
    expectedText: String,
    currentText: String?,
    expectedGeneration: Long?,
    currentGeneration: Long?
): Boolean {
    if (currentText != expectedText) return false
    return expectedGeneration == null || currentGeneration == null ||
        expectedGeneration == currentGeneration
}

private const val UNSIGNED_INT_MASK = 0xFFFF_FFFFL

private interface ClipboardSequenceUser32 : StdCallLibrary {
    fun GetClipboardSequenceNumber(): Int

    companion object {
        val INSTANCE: ClipboardSequenceUser32 by lazy {
            Native.load("user32", ClipboardSequenceUser32::class.java)
        }
    }
}
