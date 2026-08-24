package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.arch.UiEvent
import java.io.File

/**
 * One-shot events emitted by [MainStore] to be consumed exactly once by the UI.
 */
sealed interface MainEvent : UiEvent {

    /**
     * Instructs the UI to paste [translatedText] back, replacing the previously
     * selected text. Emitted after [MainIntent.ReplaceWithTranslation] completes.
     */
    data class PasteTranslation(
        val translatedText: String,
        /** После этого момента вставка небезопасна: пользователь мог сменить поле или выделение. */
        val expiresAtMillis: Long = Long.MAX_VALUE,
        /** Только Shift-сценарий показывает подтверждение после фактической вставки. */
        val showShiftFeedback: Boolean = false
    ) : MainEvent

    /** Причина, по которой фоновый Shift-перевод не смог завершиться. */
    enum class ShiftTranslationFailure {
        DISABLED,
        UNSUPPORTED_DIRECTION,
        NO_TARGET_LANGUAGE,
        TRANSLATION_FAILED
    }

    /** Просит пассивный UI заменить индикатор загрузки понятным сообщением об ошибке. */
    data class ShiftTranslationFailed(val reason: ShiftTranslationFailure) : MainEvent

    /** Последний автоматический перевод завершён: индикатор возле курсора можно скрыть. */
    data object AutoSelectionTranslationFinished : MainEvent

    data class ShowUpdateDialog(
        val newVersion: String,
        val currentVersion: String,
        val releaseNotes: String,
        val downloadUrl: String?,
        val releaseUrl: String? = null
    ) : MainEvent

    data class UpdateStatusBar(
        val code: StatusCode,
        val type: NotificationType = NotificationType.INFO,
        val isTemporary: Boolean = true
    ) : MainEvent

    /**
     * Instructs the UI to copy [text] to the system clipboard.
     * Emitted after [MainIntent.OcrAndCopyText] successfully extracts text.
     */
    data class CopyToClipboard(val text: String) : MainEvent

    data class DocumentTranslationCompleted(val outputFile: File) : MainEvent

    data class DocumentTranslationFailed(val message: String) : MainEvent
}
