package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.arch.UiEvent
import java.io.File
import com.github.ahatem.qtranslate.core.main.domain.usecase.ImageTranslationFailureKind
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslatedImageRegion

enum class PasteFeedback {
    NONE,
    ERROR_ONLY,
    CONFIRMATION_AND_ERROR
}

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
        val feedback: PasteFeedback = PasteFeedback.NONE,
        /** Связывает захват, сетевой запрос и вставку, не записывая сам текст в журнал. */
        val requestId: Long = 0L,
        /** Поколение пользовательского ввода, зафиксированное до сетевого запроса. */
        val interactionGeneration: Long? = null
    ) : MainEvent

    /** Просит пассивный UI заменить индикатор загрузки понятным сообщением об ошибке. */
    data class ShiftTranslationFailed(
        val reason: SelectionTranslationFailureReason,
        val requestId: Long = 0L
    ) : MainEvent

    /** Последний автоматический перевод завершён: индикатор возле курсора можно скрыть. */
    data object AutoSelectionTranslationFinished : MainEvent

    /** Ответ для LIVE-рамки; исходный текст нужен только для локальной дедупликации. */
    data class LiveLensTranslationCompleted(
        val requestId: Long,
        val sourceText: String,
        val translatedText: String,
        val translatorName: String
    ) : MainEvent

    /** LIVE-запрос завершился без вывода: текст уже на языке модели либо запрос не удался. */
    data class LiveLensTranslationFinished(
        val requestId: Long,
        val failure: com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationFailureKind? = null
    ) : MainEvent

    /** Локальный OCR распознал текст в графическом кадре LIVE-рамки. */
    data class LiveLensFrameRecognized(
        val requestId: Long,
        val text: String
    ) : MainEvent

    /** Распознавание кадра завершилось без пригодного текста. */
    data class LiveLensFrameRecognitionFinished(val requestId: Long) : MainEvent

    data class ImageInPlaceTranslationProgress(
        val requestId: Long,
        val completed: Int,
        val total: Int
    ) : MainEvent

    data class ImageInPlaceTranslationCompleted(
        val requestId: Long,
        val regions: List<TranslatedImageRegion>,
        val failedRegions: Int,
        val providers: Set<String>
    ) : MainEvent

    data class ImageInPlaceTranslationFailed(
        val requestId: Long,
        val kind: ImageTranslationFailureKind
    ) : MainEvent

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
