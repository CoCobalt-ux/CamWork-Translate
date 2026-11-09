package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
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
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.util.shortSummary
import com.github.michaelbull.result.fold
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/** Выполняет перевод с отдельным владельцем состояния каждого сценария и общей атомарной историей. */
class TranslateTextUseCase(
    private val scope: CoroutineScope,
    private val settingsState: StateFlow<Configuration>,
    private val activeServiceManager: ActiveServiceManager,
    private val historyRepository: HistoryRepository,
    private val summarizeUseCase: SummarizeUseCase,
    private val rewriteUseCase: RewriteUseCase,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("TranslateTextUseCase")
    private val translatorFailover = TranslatorFailover(activeServiceManager, logger::warn)
    private val translationJobs = TranslationJobCoordinator(scope)
    private val requestSequence = AtomicLong(0)

    fun cancel(lane: TranslationLane = TranslationLane.MAIN) {
        translationJobs.cancel(lane, "Перевод отменён")
    }

    suspend operator fun invoke(
        getState: () -> MainState,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (StatusCode, NotificationType, Boolean) -> Unit,
        textOverride: String? = null,
        includeExtraOutput: Boolean = true,
        applyTranslationRules: Boolean = true,
        sameLanguageFallbackTarget: LanguageCode? = null,
        recordHistory: Boolean = true,
        lane: TranslationLane = TranslationLane.MAIN,
        requestContext: TranslationRequestContext? = null,
        isRequestCurrent: () -> Boolean = { true }
    ): TranslationRunResult {
        val state = getState()
        val text = textOverride ?: state.inputText
        if (text.isBlank()) {
            cancel(lane)
            updateState { copy(isLoading = false, isExtraOutputLoading = false) }
            return TranslationRunResult.Failure(TranslationFailureKind.INVALID)
        }
        val active = activeServiceManager.getActive<Translator>(ServiceRole.TRANSLATOR)
        if (active == null) {
            cancel(lane)
            updateState { copy(isLoading = false, isExtraOutputLoading = false) }
            onStatusUpdate(StatusCode.NoTranslatorActive, NotificationType.ERROR, true)
            return TranslationRunResult.Failure(TranslationFailureKind.SERVICE_UNAVAILABLE)
        }
        val context = requestContext ?: TranslationRequestContext(requestSequence.incrementAndGet(), lane.name.lowercase())
        val historyGeneration = historyRepository.generation()
        val outcome = CompletableDeferred<TranslationRunResult>()
        val requestJob = translationJobs.launchLatest(lane, "Начат следующий перевод") {
            val startedAt = System.nanoTime()
            var completion = "cancelled"
            var effectiveTarget = state.targetLanguage
            val owner = currentCoroutineContext().job
            val publish: (MainState.() -> MainState) -> Unit = { transform ->
                translationJobs.runIfCurrent(lane, owner) {
                    if (isRequestCurrent()) updateState(transform)
                }
            }
            fun ensureCurrent() {
                owner.ensureActive()
                if (!isRequestCurrent()) throw CancellationException("Исходный текст или язык уже изменён")
            }
            try {
                ensureCurrent()
                publish { copy(isLoading = true, extraOutputText = "", isExtraOutputLoading = false) }
                onStatusUpdate(StatusCode.Translating, NotificationType.INFO, false)
                logger.info("Начат перевод: requestId=${context.requestId}, origin=${context.origin}, символов=${text.length}")

                val config = settingsState.value
                val rules = if (applyTranslationRules) config.translationRules else emptyList()
                val sourceIsAuto = state.sourceLanguage == LanguageCode.AUTO
                val initialTarget = if (sourceIsAuto) state.targetLanguage else {
                    rules.firstOrNull { it.sourceLanguage == state.sourceLanguage.tag }
                        ?.let { LanguageCode(it.targetLanguage) } ?: state.targetLanguage
                }
                var actualTarget = initialTarget
                effectiveTarget = actualTarget
                var execution = execute(active, TranslationRequest(text, state.sourceLanguage, actualTarget))
                ensureCurrent()
                if (execution == null) {
                    completion = "timeout"
                    onStatusUpdate(StatusCode.TranslationTimeout, NotificationType.ERROR, true)
                    outcome.complete(TranslationRunResult.Failure(TranslationFailureKind.TIMEOUT, active.id, active.service.name))
                    return@launchLatest
                }
                var detectedLanguage: LanguageCode? = null
                val retryTarget = execution.result.fold(
                    success = { response ->
                        detectedLanguage = response.detectedLanguage.takeIf { sourceIsAuto }
                        detectedLanguage?.let { detected ->
                            val rule = rules.firstOrNull { it.sourceLanguage == detected.tag }
                            if (rule != null) LanguageCode(rule.targetLanguage).takeIf { it != initialTarget }
                            else AutomaticTranslationTargetPolicy.fallbackForSameDetectedLanguage(
                                    detected, initialTarget, sameLanguageFallbackTarget
                                )
                        }
                    },
                    failure = { null }
                )
                if (retryTarget != null) {
                    actualTarget = retryTarget
                    effectiveTarget = actualTarget
                    execution = execute(
                        ActiveService(execution.translatorId, execution.translator),
                        TranslationRequest(text, state.sourceLanguage, actualTarget)
                    )
                    ensureCurrent()
                }
                val finalExecution = execution
                if (finalExecution == null) {
                    completion = "timeout"
                    onStatusUpdate(StatusCode.TranslationTimeout, NotificationType.ERROR, true)
                    outcome.complete(TranslationRunResult.Failure(TranslationFailureKind.TIMEOUT))
                    return@launchLatest
                }
                finalExecution.result.fold(
                    success = { response ->
                        ensureCurrent()
                        val snapshot = HistorySnapshot(
                            inputText = text,
                            translatedText = response.translatedText,
                            sourceLanguage = state.sourceLanguage.tag,
                            targetLanguage = actualTarget.tag,
                            translatorId = finalExecution.translatorId,
                            detectedSourceLanguage = detectedLanguage?.tag
                        )
                        val keepsHistory = recordHistory && settingsState.value.isHistoryEnabled
                        val expectsExtra = includeExtraOutput && settingsState.value.extraOutputType != ExtraOutputType.None
                        // Готовый ответ показывается до диска; запись переживает отмену extra-output.
                        publish {
                            copy(
                                isLoading = false,
                                translatedText = response.translatedText,
                                detectedSourceLanguage = detectedLanguage,
                                translatedTargetLanguage = actualTarget,
                                translationHistoryId = snapshot.id.takeIf { keepsHistory },
                                extraOutputText = "",
                                isExtraOutputLoading = expectsExtra
                            )
                        }
                        onStatusUpdate(StatusCode.TranslationComplete, NotificationType.SUCCESS, true)
                        val historyId = if (keepsHistory && withContext(NonCancellable) {
                            historyRepository.append(snapshot, historyGeneration)
                        }) snapshot.id else null
                        ensureCurrent()
                        publish {
                            val entries = historyRepository.entries.value
                            val position = entries.indexOfFirst { it.id == historyId }
                            copy(
                                history = entries,
                                historyIndex = if (position >= 0) position + 1 else historyIndex.coerceAtMost(entries.size),
                                translationHistoryId = historyId
                            )
                        }
                        if (expectsExtra) {
                            val extraConfig = settingsState.value
                            val extra = handleExtraOutput(
                                text, response.translatedText,
                                detectedLanguage ?: state.sourceLanguage, actualTarget,
                                ActiveService(finalExecution.translatorId, finalExecution.translator),
                                extraConfig, onStatusUpdate
                            )
                            ensureCurrent()
                            if (historyId != null) historyRepository.updateExtraOutput(
                                historyId, historyGeneration, extra, extraConfig.extraOutputType.name
                            )
                            publish { copy(extraOutputText = extra, isExtraOutputLoading = false) }
                        }
                        completion = "success"
                        outcome.complete(TranslationRunResult.Success(
                            response.translatedText, finalExecution.translatorId, finalExecution.translator.name
                        ))
                    },
                    failure = { error ->
                        ensureCurrent()
                        completion = error.toTranslationFailureKind().name.lowercase()
                        onStatusUpdate(StatusCode.TranslationFailed(error.shortSummary()), NotificationType.ERROR, true)
                        outcome.complete(TranslationRunResult.Failure(
                            error.toTranslationFailureKind(), finalExecution.translatorId, finalExecution.translator.name
                        ))
                    }
                )
            } catch (cancelled: CancellationException) {
                outcome.complete(TranslationRunResult.Failure(TranslationFailureKind.CANCELLED))
                throw cancelled
            } catch (error: Exception) {
                completion = "unexpected"
                logger.error("Неожиданная ошибка перевода: requestId=${context.requestId}, тип=${error.javaClass.simpleName}")
                onStatusUpdate(StatusCode.UnexpectedError(error.shortSummary()), NotificationType.ERROR, true)
                outcome.complete(TranslationRunResult.Failure(TranslationFailureKind.UNKNOWN))
            } finally {
                // Старый отменённый запрос уже не владеет loading следующего перевода.
                publish { copy(isLoading = false, isExtraOutputLoading = false) }
                logger.info(
                    "Перевод завершён: requestId=${context.requestId}, origin=${context.origin}, " +
                        "source=${state.sourceLanguage.tag}, target=${effectiveTarget.tag}, " +
                        "durationMs=${(System.nanoTime() - startedAt) / 1_000_000}, result=$completion"
                )
            }
        }
        requestJob.invokeOnCompletion { outcome.complete(TranslationRunResult.Failure(TranslationFailureKind.CANCELLED)) }
        try {
            requestJob.join()
            return outcome.await()
        } catch (cancelled: CancellationException) {
            requestJob.cancel(cancelled)
            throw cancelled
        }
    }

    private suspend fun execute(active: ActiveService<Translator>, request: TranslationRequest): TranslationExecution? =
        withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            translatorFailover.translate(active, request, totalTimeoutMillis = AppConstants.TRANSLATION_TIMEOUT_MS)
        }

    suspend fun refreshExtraOutput(
        getState: () -> MainState,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (StatusCode, NotificationType, Boolean) -> Unit
    ): Boolean {
        val state = getState()
        val config = settingsState.value
        if (config.extraOutputType == ExtraOutputType.None) {
            if (state.isExtraOutputLoading && !state.isLoading) cancel()
            updateState { copy(extraOutputText = "", isExtraOutputLoading = false) }
            return true
        }
        if (state.translatedText.isBlank()) return false
        val active = activeServiceManager.getActive<Translator>(ServiceRole.TRANSLATOR) ?: return true
        val historyGeneration = historyRepository.generation()
        translationJobs.launchLatest(TranslationLane.MAIN, "Изменён дополнительный вывод") {
            val owner = currentCoroutineContext().job
            val publish: (MainState.() -> MainState) -> Unit = { transform ->
                translationJobs.runIfCurrent(TranslationLane.MAIN, owner) { updateState(transform) }
            }
            try {
                publish { copy(isExtraOutputLoading = true, extraOutputText = "") }
                val text = handleExtraOutput(
                    state.inputText, state.translatedText, state.detectedSourceLanguage ?: state.sourceLanguage,
                    state.translatedTargetLanguage ?: state.targetLanguage, active, config, onStatusUpdate
                )
                currentCoroutineContext().ensureActive()
                state.translationHistoryId?.let { id ->
                    historyRepository.updateExtraOutput(id, historyGeneration, text, config.extraOutputType.name)
                }
                publish { copy(extraOutputText = text) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.error("Не удалось обновить дополнительный вывод", error)
                onStatusUpdate(StatusCode.UnexpectedError(error.shortSummary()), NotificationType.ERROR, true)
            } finally {
                publish { copy(isExtraOutputLoading = false) }
            }
        }
        return true
    }

    private suspend fun handleExtraOutput(
        input: String,
        output: String,
        source: LanguageCode,
        target: LanguageCode,
        active: ActiveService<Translator>,
        config: Configuration,
        onStatusUpdate: suspend (StatusCode, NotificationType, Boolean) -> Unit
    ): String {
        val text = if (config.extraOutputSource == ExtraOutputSource.Output) output else input
        return when (config.extraOutputType) {
            ExtraOutputType.None -> ""
            ExtraOutputType.Summarize -> summarizeUseCase(text, config, onStatusUpdate)
            ExtraOutputType.Rewrite -> rewriteUseCase(text, config, onStatusUpdate)
            ExtraOutputType.BackwardTranslate -> {
                if (source == LanguageCode.AUTO) return ""
                onStatusUpdate(StatusCode.PerformingBackwardTranslation, NotificationType.INFO, false)
                val execution = execute(active, TranslationRequest(output, target, source))
                execution?.result?.fold(
                    success = {
                        onStatusUpdate(StatusCode.TranslationComplete, NotificationType.SUCCESS, true)
                        it.translatedText
                    },
                    failure = {
                        onStatusUpdate(StatusCode.TranslationFailed(it.shortSummary()), NotificationType.WARNING, true)
                        ""
                    }
                ) ?: run {
                    onStatusUpdate(StatusCode.TranslationTimeout, NotificationType.WARNING, true)
                    ""
                }
            }
        }
    }
}
