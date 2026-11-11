package com.github.ahatem.qtranslate.ui.swing.main

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.util.UUID

internal enum class SelectionCaptureFailure {
    NO_TEXT,
    COPY_UNCONFIRMED,
    CLIPBOARD_UNAVAILABLE,
    MODIFIER_HELD,
    COPY_FAILED
}

internal sealed interface SelectionCaptureResult {
    data class Captured(val snapshot: SelectionCaptureSnapshot) : SelectionCaptureResult
    data class Failed(val reason: SelectionCaptureFailure) : SelectionCaptureResult
}

/** Одна команда копирования с ожиданием фактического обновления буфера. */
internal class ClipboardSelectionCapture(
    private val clipboard: Clipboard,
    private val simulateCopy: () -> Boolean,
    private val generation: () -> Long? = ::currentSystemClipboardGeneration,
    private val onRestoreFailure: () -> Unit = {},
    private val timeoutMillis: Long = 600L
) {
    suspend fun capture(
        guard: ClipboardInterceptionGuard,
        lease: ClipboardInterceptionGuard.Lease
    ): SelectionCaptureResult {
        val snapshot = readSnapshot()
            ?: return SelectionCaptureResult.Failed(SelectionCaptureFailure.CLIPBOARD_UNAVAILABLE)
        val marker = if (snapshot.generation == null) "qtranslate-copy-${UUID.randomUUID()}" else null
        var claim: ClipboardClaim? = null
        var readFailed = false
        try {
            currentCoroutineContext().ensureActive()
            if (marker != null) {
                val marked = runCatching { clipboard.setContents(StringSelection(marker), null) }.isSuccess
                if (!marked) return SelectionCaptureResult.Failed(SelectionCaptureFailure.CLIPBOARD_UNAVAILABLE)
                claim = ClipboardClaim(marker, null)
            }
            currentCoroutineContext().ensureActive()
            if (!simulateCopy()) return SelectionCaptureResult.Failed(SelectionCaptureFailure.COPY_FAILED)

            return withTimeoutOrNull(timeoutMillis) {
                var result: SelectionCaptureResult? = null
                while (result == null) {
                    val beforeRead = generation()
                    // Чтение запускает отложенную отрисовку форматов Windows.
                    val textRead = runCatching(::readText)
                    readFailed = textRead.isFailure
                    val text = textRead.getOrNull()
                    val updatedGeneration = generation()
                    if (marker == null && beforeRead != updatedGeneration) {
                        delay(POLL_MILLIS)
                        continue
                    }
                    val changed = if (marker == null) {
                        updatedGeneration != null && updatedGeneration != snapshot.generation
                    } else {
                        text != null && text != marker
                    }
                    if (changed && !readFailed) {
                        val contents = runCatching { clipboard.getContents(null) }.getOrNull()
                        if (marker == null && generation() != updatedGeneration) {
                            // Буфер изменился во время чтения. Снимок из разных поколений непригоден.
                            delay(POLL_MILLIS)
                            continue
                        }
                        claim = when {
                            marker != null -> ClipboardClaim(text, null)
                            updatedGeneration == nextClipboardGeneration(snapshot.generation) ->
                                ClipboardClaim(null, updatedGeneration)
                            else -> null
                        }
                        result = if (text.isNullOrBlank()) {
                            SelectionCaptureResult.Failed(SelectionCaptureFailure.NO_TEXT)
                        } else {
                            SelectionCaptureResult.Captured(
                                SelectionCaptureSnapshot(text.trim(), inspectClipboardSelectionEvidence(contents))
                            )
                        }
                    } else {
                        delay(POLL_MILLIS)
                    }
                }
                result
            } ?: SelectionCaptureResult.Failed(
                if (readFailed) SelectionCaptureFailure.CLIPBOARD_UNAVAILABLE
                else SelectionCaptureFailure.COPY_UNCONFIRMED
            )
        } finally {
            claim?.let { owned ->
                guard.restoreIfAllowed(
                    lease = lease,
                    ownsCurrentClipboard = {
                        if (owned.generation != null) generation() == owned.generation
                        else owned.text == runCatching(::readText).getOrNull()
                    },
                    restore = {
                        if (runCatching { clipboard.setContents(snapshot.contents ?: EmptyClipboard, null) }.isFailure) {
                            onRestoreFailure()
                        }
                    }
                )
            }
        }
    }

    private suspend fun readSnapshot(): ClipboardSnapshot? {
        repeat(SNAPSHOT_ATTEMPTS) { attempt ->
            val before = generation()
            val contents = runCatching { clipboard.getContents(null) }
            val after = generation()
            if (contents.isSuccess && before == after) return ClipboardSnapshot(contents.getOrNull(), after)
            if (attempt < SNAPSHOT_ATTEMPTS - 1) delay(POLL_MILLIS)
        }
        return null
    }

    private fun readText(): String? = try {
        clipboard.getData(DataFlavor.stringFlavor) as? String
    } catch (_: UnsupportedFlavorException) {
        null
    }

    private data class ClipboardSnapshot(val contents: Transferable?, val generation: Long?)
    private data class ClipboardClaim(val text: String?, val generation: Long?)

    private object EmptyClipboard : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = emptyArray()
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = false
        override fun getTransferData(flavor: DataFlavor): Any = throw UnsupportedFlavorException(flavor)
    }

    private companion object {
        const val POLL_MILLIS = 20L
        const val SNAPSHOT_ATTEMPTS = 3
    }
}
