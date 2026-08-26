package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.core.settings.data.ShiftTapTranslationMode

/** Конечное действие после локального определения направления выделенного текста. */
internal enum class SelectionTranslationAction {
    REPLACE,
    PASSIVE_OVERLAY,
    IGNORE
}

/**
 * Матрица намеренно не зависит от tray или видимости окон. Явный BIDIRECTIONAL Shift —
 * команда на замену в обоих направлениях; auto-selection и мини-кнопка остаются пассивными.
 */
internal fun resolveSelectionTranslationAction(
    trigger: SelectionTranslationTrigger,
    mode: ShiftTapTranslationMode,
    direction: ShiftSelectionDirection
): SelectionTranslationAction = when (trigger) {
    SelectionTranslationTrigger.SHIFT -> when (mode) {
        ShiftTapTranslationMode.BIDIRECTIONAL -> SelectionTranslationAction.REPLACE
        ShiftTapTranslationMode.REPLACE_ONLY ->
            if (direction == ShiftSelectionDirection.MODEL_LANGUAGE) {
                SelectionTranslationAction.REPLACE
            } else {
                SelectionTranslationAction.IGNORE
            }
        ShiftTapTranslationMode.OVERLAY_ONLY -> SelectionTranslationAction.PASSIVE_OVERLAY
        ShiftTapTranslationMode.DISABLED -> SelectionTranslationAction.IGNORE
    }

    SelectionTranslationTrigger.AUTO_SELECTION ->
        if (direction == ShiftSelectionDirection.MODEL_LANGUAGE) {
            SelectionTranslationAction.IGNORE
        } else {
            SelectionTranslationAction.PASSIVE_OVERLAY
        }

    SelectionTranslationTrigger.MANUAL_BUTTON -> SelectionTranslationAction.PASSIVE_OVERLAY
    SelectionTranslationTrigger.MANUAL_REPLACE_BUTTON -> SelectionTranslationAction.REPLACE
}

/** Переводит содержимое выделения, не съедая намеренно захваченные пробелы и переводы строк. */
internal fun restoreSelectionBoundaryWhitespace(
    originalText: String,
    translatedText: String
): String {
    val firstContent = originalText.indexOfFirst { !it.isWhitespace() }
    if (firstContent < 0) return originalText
    val lastContent = originalText.indexOfLast { !it.isWhitespace() }
    return originalText.substring(0, firstContent) + translatedText +
        originalText.substring(lastContent + 1)
}

internal sealed interface SelectionTranslationAttempt {
    data class Translated(val text: String) : SelectionTranslationAttempt
    data class Failed(val reason: SelectionTranslationFailureReason) : SelectionTranslationAttempt
}

internal sealed interface SelectionTranslationExecution {
    data object DELIVERED : SelectionTranslationExecution
    data object IGNORED : SelectionTranslationExecution
    data object REJECTED : SelectionTranslationExecution
    data class FAILED(val reason: SelectionTranslationFailureReason) : SelectionTranslationExecution
}

/**
 * Выполняет ровно один перевод и доставляет результат только в ветку выбранного действия.
 * В частности, REPLACE никогда не может незаметно провалиться в passive overlay.
 */
internal suspend fun executeSelectionTranslation(
    translationInput: String,
    action: SelectionTranslationAction,
    translate: suspend (String) -> SelectionTranslationAttempt,
    canDeliver: () -> Boolean,
    onReplace: suspend (String) -> Unit,
    onPassiveOverlay: suspend (String) -> Unit
): SelectionTranslationExecution {
    if (action == SelectionTranslationAction.IGNORE) return SelectionTranslationExecution.IGNORED

    val attempt = translate(translationInput)
    if (attempt is SelectionTranslationAttempt.Failed) {
        return SelectionTranslationExecution.FAILED(attempt.reason)
    }
    val translatedText = (attempt as SelectionTranslationAttempt.Translated).text.trim()
    if (translatedText.isBlank() || translatedText == translationInput.trim()) {
        return SelectionTranslationExecution.FAILED(SelectionTranslationFailureReason.NO_CHANGE)
    }
    if (!canDeliver()) return SelectionTranslationExecution.REJECTED

    when (action) {
        SelectionTranslationAction.REPLACE -> onReplace(translatedText)
        SelectionTranslationAction.PASSIVE_OVERLAY -> onPassiveOverlay(translatedText)
        SelectionTranslationAction.IGNORE -> error("IGNORE обработан до вызова переводчика")
    }
    return SelectionTranslationExecution.DELIVERED
}
