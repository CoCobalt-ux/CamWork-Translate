package com.github.ahatem.qtranslate.ui.swing.main

/** Снимок выделения нужен также для отказных проверок без изменения системного clipboard. */
internal data class SelectionCaptureSnapshot(
    val text: String,
    val evidence: ClipboardSelectionEvidence? = null
)

internal fun interface SelectionCaptureSource {
    suspend fun capture(): SelectionCaptureSnapshot
}
