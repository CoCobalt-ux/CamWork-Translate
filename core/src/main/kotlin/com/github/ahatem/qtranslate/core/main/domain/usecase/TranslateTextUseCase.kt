package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.history.HistorySnapshot
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveService
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import com.github.ahatem.qtranslate.core.shared.util.shortSummary
import java.util.concurrent.atomic.AtomicLong

/**
 * Translates the current input text and updates the [MainState] with the result.
 *
 * ### Job ownership
 * In-flight requests are owned by [TranslationJobCoordinator]. Each workflow uses a separate
 * [TranslationLane], so a passive selection can never cancel an explicit Shift or the main form.
 *
 * ### Translation Rules
 * When translation rules are configured, the use case resolves the correct target
 * language before translating. If the source is Auto Detect, the detected language
 * from the first translation response is used to check for a matching rule — if one
 * is found, a second translation is performed with the correct target automatically.
 * This second call only happens when:
 * 1. Source is set to Auto Detect
 * 2. A rule matches the detected language
 * 3. The rule's target differs from the current target
 */
class TranslateTextUseCase(
    private val scope: CoroutineScope,
    private val settingsState: StateFlow<Configuration>,
    private val activeServiceManager: ActiveServiceManager,
    private val historyRepository: HistoryRepository,
    private val summarizeUseCase: SummarizeUseCase,
    private val rewriteUseCase: RewriteUseCase,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("TranslateTextUseCase")
    private val translatorFailover = TranslatorFailover(activeServiceManager, logger::warn)
    private val translationJobs = TranslationJobCoordinator(scope)
    private val requestSequence = AtomicLong(0)

    fun cancel(lane: TranslationLane = TranslationLane.MAIN) {
        translationJobs.cancel(lane, "Translation cancelled")
    }

    private fun launchLatestTranslation(
        lane: TranslationLane,
        cancellationReason: String,
        block: suspend CoroutineScope.() -> Unit
    ): Job = translationJobs.launchLatest(lane, cancellationReason, block)

    suspend operator fun invoke(
        getState: () -> MainState,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
        textOverride: String? = null,
        includeExtraOutput: Boolean = true,
        applyTranslationRules: Boolean = true,
        sameLanguageFallbackTarget: LanguageCode? = null,
        lane: TranslationLane = TranslationLane.MAIN,
        requestContext: TranslationRequestContext? = null
    ): TranslationRunResult {
        val context = requestContext ?: TranslationRequestContext(
            requestId = requestSequence.incrementAndGet(),
            origin = lane.name.lowercase()
        )
        val textToTranslate = textOverride ?: getState().inputText
        if (textToTranslate.isBlank()) {
            translationJobs.cancel(lane, "Blank translation requested")
            logger.debug(
                "Translation rejected: requestId=${context.requestId}, origin=${context.origin}, " +
                    "attempt=${context.attempt}, reason=invalid_input"
            )
            return TranslationRunResult.Failure(TranslationFailureKind.INVALID)
        }

        // Resolved with its id: history records which service produced each entry, and services
        // no longer carry their own — the host composes it and keys the registry by it.
        val active = activeServiceManager.getActive<Translator>(ServiceRole.TRANSLATOR)
        if (active == null) {
            translationJobs.cancel(lane, "No translator available")
            logger.warn(
                "Translation rejected: requestId=${context.requestId}, origin=${context.origin}, " +
                    "attempt=${context.attempt}, reason=no_translator"
            )
            onStatusUpdate(StatusCode.NoTranslatorActive, NotificationType.ERROR, true)
            return TranslationRunResult.Failure(TranslationFailureKind.SERVICE_UNAVAILABLE)
        }
        val translator = active.service

        logger.info(
            "Translation started: requestId=${context.requestId}, origin=${context.origin}, " +
                "attempt=${context.attempt}, lane=$lane, service='${translator.name}', " +
                "length=${textToTranslate.length}"
        )

        val outcome = CompletableDeferred<TranslationRunResult>()

        val requestJob = launchLatestTranslation(lane, "New translation requested in $lane") {
            try {
                onStatusUpdate(StatusCode.Translating, NotificationType.INFO, false)
                updateState { copy(isLoading = true, translatedText = "", extraOutputText = "", isExtraOutputLoading = false) }

                val currentState = getState()
                // Фоновые сценарии могут задавать точный target (например, EN -> язык модели).
                // В них пользовательские правила главного окна не должны незаметно менять маршрут.
                val rules = if (applyTranslationRules) {
                    settingsState.value.translationRules
                } else {
                    emptyList()
                }
                val isAutoDetect = currentState.sourceLanguage == LanguageCode.AUTO

                // When source is explicitly set, we already know the language — apply
                // the rule immediately without needing a first-pass translation.
                val preResolvedTarget = if (!isAutoDetect) {
                    val detectedOrSelected = currentState.detectedSourceLanguage
                        ?: currentState.sourceLanguage
                    resolveTargetFromRules(detectedOrSelected, rules)
                } else null

                val initialTarget = preResolvedTarget ?: currentState.targetLanguage

                // Update UI immediately if rule changed the target before translating
                if (preResolvedTarget != null && preResolvedTarget != currentState.targetLanguage) {
                    updateState { copy(targetLanguage = preResolvedTarget) }
                }

                val request = TranslationRequest(
                    text           = textToTranslate,
                    sourceLanguage = currentState.sourceLanguage,
                    targetLanguage = initialTarget
                )

                logger.debug(
                    "Translation request: ${request.sourceLanguage} → ${request.targetLanguage}, " +
                            "length=${textToTranslate.length}"
                )

                val execution = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
                    translatorFailover.translate(
                        active = active,
                        request = request,
                        totalTimeoutMillis = AppConstants.TRANSLATION_TIMEOUT_MS
                    )
                }

                if (execution == null) {
                    logger.error(
                        "Translation failed: requestId=${context.requestId}, origin=${context.origin}, " +
                            "attempt=${context.attempt}, reason=timeout, " +
                            "elapsedLimitMs=${AppConstants.TRANSLATION_TIMEOUT_MS}"
                    )
                    updateState { copy(isLoading = false, isExtraOutputLoading = false) }
                    onStatusUpdate(StatusCode.TranslationTimeout, NotificationType.ERROR, true)
                    outcome.complete(
                        TranslationRunResult.Failure(
                            kind = TranslationFailureKind.TIMEOUT,
                            translatorId = active.id,
                            translatorName = translator.name
                        )
                    )
                    return@launchLatestTranslation
                }

                execution.result.fold(
                    success = { response ->
                        // Текст моделей не должен попадать в production-логи. Для диагностики
                        // достаточно сервиса, длины и факта успешного ответа.
                        logger.info(
                            "Translation response: requestId=${context.requestId}, " +
                                "origin=${context.origin}, attempt=${context.attempt}, " +
                                "service='${execution.translator.name}', " +
                                "resultLength=${response.translatedText.length}"
                        )

                        val detectedLanguage = response.detectedLanguage
                            .takeIf { isAutoDetect }

                        // Re-translate if Auto Detect revealed a rule match
                        // Only triggers when:
                        // 1. Source was Auto Detect
                        // 2. A language was detected from the response
                        // 3. A rule matches the detected language
                        // 4. The rule target differs from what we just translated to
                        if (isAutoDetect && detectedLanguage != null) {
                            val ruleTarget = resolveTargetFromRules(detectedLanguage, rules)
                            val automaticFallback = if (ruleTarget == null) {
                                AutomaticTranslationTargetPolicy.fallbackForSameDetectedLanguage(
                                    detectedLanguage = detectedLanguage,
                                    requestedTarget = initialTarget,
                                    modelLanguage = sameLanguageFallbackTarget
                                )
                            } else {
                                null
                            }
                            val resolvedRetryTarget = ruleTarget
                                ?.takeIf { it != initialTarget }
                                ?: automaticFallback

                            if (resolvedRetryTarget != null) {
                                logger.debug(
                                    "Auto-detected '${detectedLanguage.tag}' matches target " +
                                        "'${initialTarget.tag}' or a rule changed it; " +
                                        "re-translating to '${resolvedRetryTarget.tag}'"
                                )
                                val retryOutcome = handleReTranslation(
                                    textToTranslate  = textToTranslate,
                                    sourceLanguage   = currentState.sourceLanguage,
                                    ruleTarget       = resolvedRetryTarget,
                                    detectedLanguage = detectedLanguage,
                                    currentState     = currentState,
                                    active            = ActiveService(
                                        execution.translatorId,
                                        execution.translator
                                    ),
                                    updateState      = updateState,
                                    onStatusUpdate   = onStatusUpdate,
                                    includeExtraOutput = includeExtraOutput,
                                    context = context.copy(attempt = context.attempt + 1)
                                )
                                outcome.complete(retryOutcome)
                                return@launchLatestTranslation
                            }
                        }

                        // ---- Normal path — no re-translation needed ----
                        onStatusUpdate(StatusCode.TranslationComplete, NotificationType.SUCCESS, true)

                        val (newHistory, newHistoryIndex) = buildHistory(
                            currentState, textToTranslate, response.translatedText, execution.translatorId,
                            detectedSourceLanguage = detectedLanguage?.tag
                        )

                        // Publish the primary translation before requesting extra output —
                        // the two are independent and the user should not wait for a second
                        // round trip to read the first result.
                        publishPrimaryThenExtraOutput(
                            inputText          = textToTranslate,
                            translatedText    = response.translatedText,
                            detectedLanguage  = detectedLanguage,
                            history           = newHistory,
                            historyIndex      = newHistoryIndex,
                            sourceForBackward = detectedLanguage ?: currentState.sourceLanguage,
                            targetForBackward = initialTarget,
                            activeTranslator  = ActiveService(
                                execution.translatorId,
                                execution.translator
                            ),
                            updateState       = updateState,
                            onStatusUpdate    = onStatusUpdate,
                            includeExtraOutput = includeExtraOutput
                        )
                        outcome.complete(
                            TranslationRunResult.Success(
                                translatedText = response.translatedText,
                                translatorId = execution.translatorId,
                                translatorName = execution.translator.name
                            )
                        )
                    },
                    failure = { error ->
                        val kind = error.toTranslationFailureKind()
                        logger.error(
                            "Translation failed: requestId=${context.requestId}, " +
                                "origin=${context.origin}, attempt=${context.attempt}, " +
                                "service='${execution.translator.name}', reason=$kind, " +
                                "type=${error::class.simpleName}"
                        )
                        updateState { copy(isLoading = false, isExtraOutputLoading = false) }
                        val summary = error.shortSummary()
                        onStatusUpdate(StatusCode.TranslationFailed(summary), NotificationType.ERROR, true)
                        outcome.complete(
                            TranslationRunResult.Failure(
                                kind = kind,
                                translatorId = execution.translatorId,
                                translatorName = execution.translator.name
                            )
                        )
                    }
                )

            } catch (e: CancellationException) {
                logger.debug(
                    "Translation cancelled: requestId=${context.requestId}, " +
                        "origin=${context.origin}, attempt=${context.attempt}, lane=$lane"
                )
                outcome.complete(TranslationRunResult.Failure(TranslationFailureKind.CANCELLED))
                throw e
            } catch (e: Exception) {
                logger.error(
                    "Unexpected translation error: requestId=${context.requestId}, " +
                        "origin=${context.origin}, attempt=${context.attempt}, lane=$lane, " +
                        "type=${e::class.simpleName}"
                )
                updateState { copy(isLoading = false, isExtraOutputLoading = false) }
                val summary = e.shortSummary()
                onStatusUpdate(StatusCode.UnexpectedError(summary), NotificationType.ERROR, true)
                outcome.complete(TranslationRunResult.Failure(TranslationFailureKind.UNKNOWN))
            }
        }

        requestJob.invokeOnCompletion {
            outcome.complete(TranslationRunResult.Failure(TranslationFailureKind.CANCELLED))
        }
        requestJob.join()
        return outcome.await()
    }

    /**
     * Performs a second translation when Auto Detect revealed a rule match.
     * Updates state with the final result and correct target language.
     */
    private suspend fun handleReTranslation(
        textToTranslate: String,
        sourceLanguage: LanguageCode,
        ruleTarget: LanguageCode,
        detectedLanguage: LanguageCode,
        currentState: MainState,
        active: ActiveService<Translator>,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
        includeExtraOutput: Boolean,
        context: TranslationRequestContext
    ): TranslationRunResult {
        logger.info(
            "Translation retry started: requestId=${context.requestId}, origin=${context.origin}, " +
                "attempt=${context.attempt}, service='${active.service.name}', length=${textToTranslate.length}"
        )
        val retryRequest = TranslationRequest(
            text           = textToTranslate,
            sourceLanguage = sourceLanguage,
            targetLanguage = ruleTarget
        )

        val retryExecution = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            translatorFailover.translate(
                active = active,
                request = retryRequest,
                totalTimeoutMillis = AppConstants.TRANSLATION_TIMEOUT_MS
            )
        }

        if (retryExecution == null) {
            logger.error(
                "Translation retry failed: requestId=${context.requestId}, origin=${context.origin}, " +
                    "attempt=${context.attempt}, reason=timeout"
            )
            updateState { copy(isLoading = false, isExtraOutputLoading = false) }
            onStatusUpdate(StatusCode.TranslationTimeout, NotificationType.ERROR, true)
            return TranslationRunResult.Failure(
                kind = TranslationFailureKind.TIMEOUT,
                translatorId = active.id,
                translatorName = active.service.name
            )
        }

        return retryExecution.result.fold(
            success = { retryResponse ->
                logger.info(
                    "Translation retry response: requestId=${context.requestId}, " +
                        "origin=${context.origin}, attempt=${context.attempt}, " +
                        "service='${retryExecution.translator.name}', " +
                        "resultLength=${retryResponse.translatedText.length}"
                )
                onStatusUpdate(StatusCode.TranslationComplete, NotificationType.SUCCESS, true)

                val (newHistory, newHistoryIndex) = buildHistory(
                    currentState, textToTranslate, retryResponse.translatedText,
                    retryExecution.translatorId,
                    detectedSourceLanguage = detectedLanguage.tag
                )

                publishPrimaryThenExtraOutput(
                    inputText          = textToTranslate,
                    translatedText    = retryResponse.translatedText,
                    detectedLanguage  = detectedLanguage,
                    history           = newHistory,
                    historyIndex      = newHistoryIndex,
                    sourceForBackward = detectedLanguage,
                    targetForBackward = ruleTarget,
                    activeTranslator  = ActiveService(
                        retryExecution.translatorId,
                        retryExecution.translator
                    ),
                    updateState       = updateState,
                    onStatusUpdate    = onStatusUpdate,
                    ruleTarget        = ruleTarget,
                    includeExtraOutput = includeExtraOutput
                )
                TranslationRunResult.Success(
                    translatedText = retryResponse.translatedText,
                    translatorId = retryExecution.translatorId,
                    translatorName = retryExecution.translator.name
                )
            },
            failure = { error ->
                val kind = error.toTranslationFailureKind()
                logger.error(
                    "Translation retry failed: requestId=${context.requestId}, " +
                        "origin=${context.origin}, attempt=${context.attempt}, " +
                        "service='${retryExecution.translator.name}', reason=$kind, " +
                        "type=${error::class.simpleName}"
                )
                updateState { copy(isLoading = false, isExtraOutputLoading = false) }
                val summary = error.shortSummary()
                onStatusUpdate(StatusCode.TranslationFailed(summary), NotificationType.ERROR, true)
                TranslationRunResult.Failure(
                    kind = kind,
                    translatorId = retryExecution.translatorId,
                    translatorName = retryExecution.translator.name
                )
            }
        )
    }

    // -------------------------------------------------------------------------
    // Rules
    // -------------------------------------------------------------------------

    private fun resolveTargetFromRules(
        detectedSource: LanguageCode,
        rules: List<TranslationRule>
    ): LanguageCode? =
        rules.firstOrNull { it.sourceLanguage == detectedSource.tag }
            ?.let { LanguageCode(it.targetLanguage) }

    // -------------------------------------------------------------------------
    // History
    // -------------------------------------------------------------------------

    private fun buildHistory(
        currentState: MainState,
        inputText: String,
        translatedText: String,
        translatorId: String,
        detectedSourceLanguage: String? = null
    ): Pair<List<HistorySnapshot>, Int> {
        if (!settingsState.value.isHistoryEnabled) {
            return currentState.history to currentState.historyIndex
        }

        val snapshot = HistorySnapshot(
            inputText              = inputText,
            translatedText         = translatedText,
            sourceLanguage         = currentState.sourceLanguage.tag,
            targetLanguage         = currentState.targetLanguage.tag,
            translatorId           = translatorId,
            detectedSourceLanguage = detectedSourceLanguage
        )

        // Truncate any "future" entries that were undone before this new translation,
        // then append the new snapshot and cap at the configured maximum.
        val past    = currentState.history.take(currentState.historyIndex)
        val updated = (past + snapshot).takeLast(AppConstants.MAX_HISTORY_ENTRIES)

        logger.debug("History: ${updated.size}/${AppConstants.MAX_HISTORY_ENTRIES} entries")
        return updated to updated.size
    }

    /** Patches the last snapshot in [history] with the resolved extra output. */
    private fun patchExtraOutput(
        history: List<HistorySnapshot>,
        extraOutputText: String,
        extraOutputType: String
    ): List<HistorySnapshot> {
        if (history.isEmpty() || (extraOutputText.isEmpty() && extraOutputType == "None")) return history
        val patched = history.last().copy(
            extraOutputText = extraOutputText,
            extraOutputType = extraOutputType
        )
        return history.dropLast(1) + patched
    }

    // -------------------------------------------------------------------------
    // Extra output
    // -------------------------------------------------------------------------

    /**
     * Recomputes only the extra output, leaving the translation on screen untouched.
     *
     * Switching the extra panel between backward, summary and rewrite used to dispatch a full
     * translation. That wiped the translation the user was reading, showed a spinner over it, and
     * spent a second request on the translator for a result already on screen — on the unofficial
     * endpoints, a rate limit risked for nothing. Changing summary length or rewrite style did the
     * same, which is harder still to justify, since only the extra panel's own parameter moved.
     *
     * Nothing about the extra output needs the translation to be redone: [handleExtraOutput] takes
     * the translated text as a parameter, so it can be fed the text already in state.
     *
     * Returns false when there is nothing to work from, leaving the caller to fall back to a real
     * translation rather than showing an empty panel.
     */
    suspend fun refreshExtraOutput(
        getState: () -> MainState,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ): Boolean {
        val state = getState()
        val extraOutputType = settingsState.value.extraOutputType

        if (extraOutputType == ExtraOutputType.None) {
            translationJobs.cancel(TranslationLane.MAIN, "Extra output disabled")
            updateState { copy(extraOutputText = "", isExtraOutputLoading = false) }
            return true
        }

        // Nothing translated yet, so there is no result to derive from. Summarising the input
        // would still need the input, and backward translation needs the output either way.
        if (state.translatedText.isBlank()) return false

        val activeTranslator = activeServiceManager.getActive<Translator>(ServiceRole.TRANSLATOR)
            ?: run {
                logger.warn("No translator service available")
                onStatusUpdate(StatusCode.NoTranslatorActive, NotificationType.ERROR, true)
                return true
            }

        // Cancels any extra-output request still in flight, so switching type twice quickly
        // cannot land the first answer under the second choice.
        launchLatestTranslation(TranslationLane.MAIN, "Extra output type changed") {
            updateState { copy(extraOutputText = "", isExtraOutputLoading = true) }
            try {
                val extraOutput = handleExtraOutput(
                    inputText         = state.inputText,
                    targetText        = state.translatedText,
                    sourceForBackward = state.detectedSourceLanguage ?: state.sourceLanguage,
                    targetForBackward = state.targetLanguage,
                    activeTranslator  = activeTranslator,
                    onStatusUpdate    = onStatusUpdate
                )

                val patched = patchExtraOutput(getState().history, extraOutput, extraOutputType.name)
                updateState {
                    copy(
                        extraOutputText      = extraOutput,
                        isExtraOutputLoading = false,
                        history              = patched
                    )
                }
                if (settingsState.value.isHistoryEnabled) historyRepository.saveHistory(patched)
            } finally {
                // Switching type twice quickly cancels the first request. Without this the panel
                // would keep the spinner of a request whose answer is never coming.
                updateState { copy(isExtraOutputLoading = false) }
            }
        }
        return true
    }

    /**
     * Publishes the primary translation immediately, then fills in the extra output when it
     * arrives.
     *
     * The two results come from independent requests, so waiting for the second before showing
     * the first made every translation feel as slow as the slowest of the pair. The primary
     * result is written first and [MainState.isExtraOutputLoading] carries the secondary
     * request, letting the extra panel show its own progress while the translation is already
     * readable.
     *
     * Runs inside the caller's `translationJob`, so a new translation cancels an in-flight
     * extra-output request and a stale result can never overwrite a newer translation.
     *
     * History is saved only after the extra output lands, so the persisted snapshot still
     * contains it.
     *
     * @param ruleTarget Set only on the rule-driven path, where the target language changed
     *   as a result of the detected source language.
     */
    private suspend fun publishPrimaryThenExtraOutput(
        inputText: String,
        translatedText: String,
        detectedLanguage: LanguageCode?,
        history: List<HistorySnapshot>,
        historyIndex: Int,
        sourceForBackward: LanguageCode,
        targetForBackward: LanguageCode,
        activeTranslator: ActiveService<Translator>,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
        ruleTarget: LanguageCode? = null,
        includeExtraOutput: Boolean = true
    ) {
        val extraOutputType = settingsState.value.extraOutputType
        val expectsExtraOutput = includeExtraOutput && extraOutputType != ExtraOutputType.None

        updateState {
            copy(
                isLoading              = false,
                translatedText         = translatedText,
                detectedSourceLanguage = detectedLanguage,
                targetLanguage         = ruleTarget ?: targetLanguage,
                history                = history,
                historyIndex           = historyIndex,
                extraOutputText        = "",
                isExtraOutputLoading   = expectsExtraOutput
            )
        }

        if (!expectsExtraOutput) {
            if (settingsState.value.isHistoryEnabled) historyRepository.saveHistory(history)
            return
        }

        val extraOutput = try {
            handleExtraOutput(
                inputText         = inputText,
                targetText        = translatedText,
                sourceForBackward = sourceForBackward,
                targetForBackward = targetForBackward,
                activeTranslator  = activeTranslator,
                onStatusUpdate    = onStatusUpdate
            )
        } catch (cancellation: CancellationException) {
            // Cancelling a translation while its extra output was still in flight left the panel
            // spinning for an answer that had been abandoned, and only another translation cleared
            // it. The flag belongs to the request, so it is lowered with the request.
            updateState { copy(isExtraOutputLoading = false) }
            throw cancellation
        }

        val finalHistory = patchExtraOutput(history, extraOutput, extraOutputType.name)

        updateState {
            copy(
                extraOutputText      = extraOutput,
                isExtraOutputLoading = false,
                history              = finalHistory
            )
        }

        if (settingsState.value.isHistoryEnabled) historyRepository.saveHistory(finalHistory)
    }

    private suspend fun handleExtraOutput(
        inputText: String,
        targetText: String,
        sourceForBackward: LanguageCode,
        targetForBackward: LanguageCode,
        activeTranslator: ActiveService<Translator>,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
    ): String {
        val config = settingsState.value
        // ExtraOutputSource determines whether we operate on the original input text
        // or on the translated output. Resolved by the caller before this is called.
        val sourceText = when (config.extraOutputSource) {
            ExtraOutputSource.Output -> targetText
            ExtraOutputSource.Input  -> inputText
        }
        return when (config.extraOutputType) {
            ExtraOutputType.BackwardTranslate -> performBackwardTranslation(
                targetText     = targetText,
                targetLanguage = sourceForBackward,
                sourceLanguage = targetForBackward,
                activeTranslator = activeTranslator,
                onStatusUpdate = onStatusUpdate
            )
            ExtraOutputType.Summarize -> summarizeUseCase(
                text           = sourceText,
                config         = config,
                onStatusUpdate = onStatusUpdate
            )
            ExtraOutputType.Rewrite -> rewriteUseCase(
                text           = sourceText,
                config         = config,
                onStatusUpdate = onStatusUpdate
            )
            ExtraOutputType.None -> ""
        }
    }

    private suspend fun performBackwardTranslation(
        targetText: String,
        targetLanguage: LanguageCode,
        sourceLanguage: LanguageCode,
        activeTranslator: ActiveService<Translator>,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
    ): String {
        if (targetLanguage == LanguageCode.AUTO) {
            logger.warn("Cannot perform backward translation — target language is AUTO")
            return "Cannot translate back to Auto-Detect."
        }

        onStatusUpdate(StatusCode.PerformingBackwardTranslation, NotificationType.INFO, false)

        val execution = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            translatorFailover.translate(
                activeTranslator,
                TranslationRequest(targetText, sourceLanguage, targetLanguage),
                totalTimeoutMillis = AppConstants.TRANSLATION_TIMEOUT_MS
            )
        }

        return if (execution == null) {
            logger.error("Backward translation timed out")
            onStatusUpdate(StatusCode.TranslationComplete, NotificationType.SUCCESS, true)
            "Backward translation timed out."
        } else {
            execution.result.fold(
                success = { response ->
                    logger.debug("Backward translation successful")
                    onStatusUpdate(StatusCode.TranslationComplete, NotificationType.SUCCESS, true)
                    response.translatedText
                },
                failure = { error ->
                    logger.error(
                        "Backward translation failed: service='${execution.translator.name}', " +
                            "type=${error::class.simpleName}"
                    )
                    onStatusUpdate(StatusCode.TranslationComplete, NotificationType.SUCCESS, true)
                    "Backward translation failed: ${error.message}"
                }
            )
        }
    }
}
