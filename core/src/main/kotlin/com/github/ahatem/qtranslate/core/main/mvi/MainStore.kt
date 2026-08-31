package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.document.DocumentTranslationException
import com.github.ahatem.qtranslate.core.document.DocumentTranslationRequest
import com.github.ahatem.qtranslate.core.document.DocumentTranslationUseCase
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.core.main.domain.usecase.*
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ShiftTapTranslationMode
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.arch.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * MVI store for the main translation screen.
 *
 * ### Responsibilities
 * - Owning [MainState] and exposing it as a [StateFlow]
 * - Routing [MainIntent]s to the appropriate use cases
 * - Emitting one-shot [MainEvent]s for the UI (e.g. status bar messages)
 * - Setting up background observers for instant translation and spell checking
 *
 * ### Dispatch model
 * Simple synchronous state mutations (text input, language selection, etc.) are
 * applied directly via [MutableStateFlow.update] without launching a coroutine.
 * Only intents that require suspend operations launch on [scope].
 * This prevents out-of-order state updates from rapid synchronous dispatches.
 *
 * ### OCR flow
 * [MainIntent.OcrAndTranslateImage] extracts text via [OcrAndTranslateUseCase],
 * writes the result into [MainState.inputText], then triggers translation — the
 * same path as if the user had typed the text manually.
 */
class MainStore(
    private val scope: CoroutineScope,
    private val settingsState: StateFlow<Configuration>,
    private val historyRepository: HistoryRepository,
    private val checkForUpdatesUseCase: CheckForUpdatesUseCase,
    private val handleTextToSpeechUseCase: HandleTextToSpeechUseCase,
    private val performSpellCheckUseCase: PerformSpellCheckUseCase,
    private val selectActiveServiceUseCase: SelectActiveServiceUseCase,
    private val translateTextUseCase: TranslateTextUseCase,
    private val swapLanguagesUseCase: SwapLanguagesUseCase,
    private val ocrAndTranslateUseCase: OcrAndTranslateUseCase,
    private val summarizeUseCase: SummarizeUseCase,
    private val rewriteUseCase: RewriteUseCase,
    private val lookupWordUseCase: LookupWordUseCase,
    private val searchImagesUseCase: SearchImagesUseCase,
    private val fetchInlineDefinitionUseCase: FetchInlineDefinitionUseCase,
    private val documentTranslationUseCase: DocumentTranslationUseCase
) : Store<MainState, MainIntent, MainEvent> {

    private var documentTranslationJob: Job? = null
    private var documentTranslationGeneration = 0L
    private val instantTranslationCoordinator = InstantTranslationCoordinator()
    private val userInputChanges = MutableSharedFlow<UserInputRevision>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val selectionTranslationCoordinator = SelectionTranslationCoordinator()
    private val selectionRequestLock = Any()
    private val selectionTranslationJobs = mutableMapOf<SelectionTranslationTicket, Job>()
    private val shiftTranslationMemory = ShiftTranslationMemory()

    private val _state = MutableStateFlow(
        MainState(
            isDictionaryPanelVisible = settingsState.value.showDictionaryPanel,
            isQuickDictionaryPinned  = settingsState.value.isQuickDictionaryPinned,
            targetLanguage           = LanguageCode(settingsState.value.preferredTargetLanguage),
            sourceLanguage           = LanguageCode(settingsState.value.preferredSourceLanguage)
        )
    )
    override val state: StateFlow<MainState> = _state.asStateFlow()

    private val _eventChannel = Channel<MainEvent>(Channel.BUFFERED)
    override val events: Flow<MainEvent> = _eventChannel.receiveAsFlow()

    init {
        loadInitialHistory()
        observeAvailableServices()
        observeInstantTranslation()
        observeSpellChecking()
        observeTtsPlayback()
        checkForUpdates()
    }

    // -------------------------------------------------------------------------
    // Init observers
    // -------------------------------------------------------------------------

    private fun loadInitialHistory() {
        scope.launch {
            val history = historyRepository.loadHistory()
            _state.update { it.copy(history = history, historyIndex = history.size) }
        }
    }

    private fun observeAvailableServices() {
        scope.launch {
            selectActiveServiceUseCase.observe().collect { selection ->
                _state.update { current ->
                    val sortedLanguages = selection.availableLanguages.sortedWith(
                        compareBy<LanguageCode> { lc -> lc.tag != "auto" }
                            .thenBy { lc -> lc.getDisplayName() }
                    )

                    // If the previously selected target language is not available in the new
                    // list (e.g. because pinnedLanguages hides it, or the translator doesn't
                    // support it), fall back to the saved preference then to the first available
                    // non-auto language so the user is never silently translated to the wrong language.
                    val targetLang = when {
                        current.targetLanguage in sortedLanguages -> current.targetLanguage
                        else -> {
                            val preferred = LanguageCode(settingsState.value.preferredTargetLanguage)
                            sortedLanguages.firstOrNull { it == preferred }
                                ?: sortedLanguages.firstOrNull { it.tag != "auto" }
                                ?: current.targetLanguage
                        }
                    }

                    current.copy(
                        availableServices  = selection.availableServices,
                        availableLanguages = sortedLanguages,
                        serviceOptions     = selection.serviceOptions,
                        targetLanguage     = targetLang
                    )
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeInstantTranslation() {
        // Только пользовательский ввод попадает в поток. История, Shift-overlay и quick popup
        // меняют MainState программно и больше не порождают скрытый второй сетевой запрос.
        scope.launch {
            userInputChanges.collect { revision ->
                val text = revision.text
                if (!instantTranslationCoordinator.shouldAutoTranslate(revision)) return@collect
                    if (settingsState.value.isInstantTranslationEnabled && text.isBlank()) {
                        translateTextUseCase.cancel(TranslationLane.MAIN)
                        _state.update {
                            it.copy(
                                translatedText = "",
                                extraOutputText = "",
                                detectedSourceLanguage = null,
                                isLoading = false
                            )
                        }
                    }
            }
        }

        // Debounced translation — only fires when there is enough text to translate.
        scope.launch {
            userInputChanges
                .debounce(AppConstants.INSTANT_TRANSLATION_DEBOUNCE_MS)
                .collect { revision ->
                    if (!instantTranslationCoordinator.shouldAutoTranslate(revision)) return@collect
                    val text = revision.text
                    if (settingsState.value.isInstantTranslationEnabled
                        && text.length >= AppConstants.INSTANT_TRANSLATE_MIN_CHARS
                    ) {
                        val modelLanguage = runCatching {
                            LanguageCode(settingsState.value.modelLanguage)
                        }.getOrDefault(LanguageCode.RUSSIAN)
                        translateText(
                            textOverride = text,
                            sameLanguageFallbackTarget = modelLanguage
                        )
                    }
                }
        }
    }

    /** Mirrors [HandleTextToSpeechUseCase.isPlaying] into [MainState.isTtsPlaying]
     *  so the UI can reactively toggle listen ↔ stop buttons. */
    private fun observeTtsPlayback() {
        scope.launch {
            handleTextToSpeechUseCase.isPlaying.collect { playing ->
                _state.update { it.copy(isTtsPlaying = playing) }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeSpellChecking() {
        scope.launch {
            combine(
                state.map { it.inputText }.distinctUntilChanged(),
                settingsState.map { it.isSpellCheckingEnabled }.distinctUntilChanged()
            ) { text, isEnabled -> text to isEnabled }
                .debounce(AppConstants.SPELL_CHECK_DEBOUNCE_MS)
                .collect { (text, isEnabled) -> handleSpellCheck(text, isEnabled) }
        }
    }

    private fun checkForUpdates() {
        scope.launch {
            checkForUpdatesUseCase(onStatusUpdate = ::updateStatusBar)
        }
    }

    // -------------------------------------------------------------------------
    // Intent dispatch
    // -------------------------------------------------------------------------

    override fun dispatch(intent: MainIntent) {
        when (intent) {

            // ---- Synchronous state mutations — no coroutine needed ----

            is MainIntent.UpdateInputText -> {
                // Remove line breaks if enabled — replaces \n with space so
                // PDF-copied text translates as complete sentences (Mohamed's request)
                val cleaned = if (settingsState.value.isRemoveLineBreaksEnabled)
                    intent.text.replace("\n", " ").replace("\r", "").replace("  ", " ").trim()
                else intent.text
                _state.update { it.copy(inputText = cleaned, detectedSourceLanguage = null) }
                userInputChanges.tryEmit(instantTranslationCoordinator.recordUserInput(cleaned))
                // With instant translate enabled, cancel any in-flight translation immediately
                // so the loading indicator clears and the debounce can queue the next request.
                // Without this, the collect coroutine in observeInstantTranslation stays
                // suspended at join() until the current translation finishes — the user's new
                // text effectively waits in line behind the old result.
                // Capture state once to avoid reading _state.value twice (TOCTOU race).
                if (settingsState.value.isInstantTranslationEnabled && _state.value.isLoading) {
                    translateTextUseCase.cancel(TranslationLane.MAIN)
                    _state.update { s -> s.copy(isLoading = false) }
                }
            }

            is MainIntent.SelectSourceLanguage ->
                _state.update { it.copy(sourceLanguage = intent.language, detectedSourceLanguage = null) }

            is MainIntent.SelectTargetLanguage ->
                _state.update { it.copy(targetLanguage = intent.language) }

            is MainIntent.ApplyCorrection -> {
                val corrected = _state.value.inputText.replaceFirst(intent.original, intent.suggestion)
                _state.update { it.copy(inputText = corrected) }
                userInputChanges.tryEmit(instantTranslationCoordinator.recordUserInput(corrected))
            }

            // Closing clears the pin. A pin says "keep this one around", not "and every one
            // after it" — leaving it set meant the next popup opened wearing the pinned border
            // and then auto-hid anyway, which is the worst of both.
            MainIntent.HideQuickTranslate ->
                _state.update {
                    it.copy(
                        isQuickTranslateDialogVisible = false,
                        isQuickTranslateDialogPinned = false,
                        isQuickTranslateDialogPassive = false,
                        quickTranslateSourceLanguageOverride = null,
                        quickTranslateTargetLanguageOverride = null,
                        quickTranslateDetectedLanguageOverride = null
                    )
                }

            MainIntent.ToggleQuickTranslateDialogPin ->
                // Use `it` from the update lambda — not _state.value — to avoid
                // a data race between the value read and the update being applied.
                _state.update { it.copy(isQuickTranslateDialogPinned = !it.isQuickTranslateDialogPinned) }

            MainIntent.UndoTranslation -> handleUndo()
            MainIntent.RedoTranslation -> handleRedo()
            MainIntent.CycleTargetLanguage -> handleCycleTargetLanguage()
            is MainIntent.RestoreHistoryEntry -> handleRestoreHistoryEntry(intent)
            MainIntent.ClearHistory -> scope.launch { handleClearHistory() }

            // ---- Async operations — launched on scope ----

            MainIntent.SwapLanguages -> scope.launch { swapLanguages(SwapLanguagesContext.MAIN) }
            MainIntent.SwapQuickTranslateLanguages ->
                scope.launch { swapLanguages(SwapLanguagesContext.QUICK_TRANSLATE) }
            MainIntent.CheckForUpdates -> checkForUpdates()
            is MainIntent.TranslateDocument -> startDocumentTranslation(intent)
            MainIntent.CancelDocumentTranslation -> cancelDocumentTranslation()
            MainIntent.PerformSpellCheck -> scope.launch {
                handleSpellCheck(_state.value.inputText, isEnabled = true)
            }

            is MainIntent.Translate -> {
                instantTranslationCoordinator.markCurrentAsExplicit()
                scope.launch { translateText(intent.text) }
            }

            MainIntent.RefreshExtraOutput -> scope.launch { refreshExtraOutput() }

            MainIntent.CancelTranslation -> {
                translateTextUseCase.cancel(TranslationLane.MAIN)
                // Both flags, because cancelling can land while the extra panel is still waiting
                // on its own request and only the main one was ever cleared here.
                _state.update { it.copy(isLoading = false, isExtraOutputLoading = false) }
                scope.launch {
                    updateStatusBar(StatusCode.TranslationCancelled, NotificationType.INFO, true)
                }
            }

            MainIntent.NotifyTextCopied -> scope.launch {
                updateStatusBar(StatusCode.TextCopied, NotificationType.SUCCESS, true)
            }

            MainIntent.StopTTS -> handleTextToSpeechUseCase.stop()

            is MainIntent.ReplaceWithTranslation -> scope.launch {
                handleReplaceWithTranslation(intent.selectedText)
            }

            is MainIntent.TranslateShiftSelection -> {
                startSelectionTranslation(
                    selectedText = intent.selectedText,
                    capturedAtMillis = intent.capturedAtMillis,
                    requestId = intent.requestId,
                    trigger = SelectionTranslationTrigger.SHIFT
                )
            }

            is MainIntent.AutoTranslateSelection -> {
                startSelectionTranslation(
                    selectedText = intent.selectedText,
                    capturedAtMillis = intent.capturedAtMillis,
                    requestId = intent.requestId,
                    trigger = SelectionTranslationTrigger.AUTO_SELECTION
                )
            }

            is MainIntent.TranslateSelectionFromButton -> {
                startSelectionTranslation(
                    selectedText = intent.selectedText,
                    capturedAtMillis = intent.capturedAtMillis,
                    requestId = intent.requestId,
                    trigger = SelectionTranslationTrigger.MANUAL_BUTTON
                )
            }

            is MainIntent.TranslateSelectionAndReplaceFromButton -> {
                startSelectionTranslation(
                    selectedText = intent.selectedText,
                    capturedAtMillis = intent.capturedAtMillis,
                    requestId = intent.requestId,
                    trigger = SelectionTranslationTrigger.MANUAL_REPLACE_BUTTON,
                    interactionGeneration = intent.interactionGeneration
                )
            }

            is MainIntent.ReportShiftCaptureFailure -> scope.launch {
                _eventChannel.send(
                    MainEvent.ShiftTranslationFailed(
                        reason = SelectionTranslationFailureReason.CAPTURE,
                        requestId = intent.requestId
                    )
                )
            }

            MainIntent.CancelSelectionTranslations -> cancelSelectionTranslations()

            is MainIntent.TranslateLiveLensText -> scope.launch {
                handleLiveLensTranslation(intent)
            }

            MainIntent.CancelLiveLensTranslation ->
                translateTextUseCase.cancel(TranslationLane.LIVE_LENS)

            is MainIntent.ListenToText -> scope.launch {
                handleListen(intent.textSource, intent.text, intent.language)
            }

            is MainIntent.OcrAndTranslateImage -> scope.launch {
                handleOcrAndTranslate(intent)
            }

            is MainIntent.OcrAndCopyText -> scope.launch {
                handleOcrAndCopyText(intent)
            }

            is MainIntent.ShowQuickTranslate -> scope.launch {
                handleShowQuickTranslate(intent)
            }

            is MainIntent.LookupWord -> scope.launch { handleLookupWord(intent) }

            is MainIntent.ToggleDictionaryPanel -> _state.update {
                it.copy(isDictionaryPanelVisible = !it.isDictionaryPanelVisible)
            }

            is MainIntent.ShowQuickDictionary -> scope.launch {
                // Pre-set dictionaryWord so the dialog's search field is already populated
                // on the very first render — before handleLookupWord emits its own update.
                _state.update {
                    it.copy(
                        isQuickDictionaryVisible = true,
                        // A pin belongs to the popup that was pinned, not to the next one.
                        isQuickDictionaryPinned =
                            if (it.isQuickDictionaryVisible) it.isQuickDictionaryPinned else false,
                        dictionaryWord   = if (intent.selectedText.isNotBlank()) intent.selectedText else it.dictionaryWord,
                        isDictionaryLoading = intent.selectedText.isNotBlank(),
                        quickDictionaryTriggerCount = it.quickDictionaryTriggerCount + 1
                    )
                }
                if (intent.selectedText.isNotBlank()) {
                    handleLookupWord(MainIntent.LookupWord(intent.selectedText, intent.language))
                }
            }

            is MainIntent.HideQuickDictionary -> _state.update {
                it.copy(isQuickDictionaryVisible = false, isQuickDictionaryPinned = false)
            }

            is MainIntent.ToggleQuickDictionaryPin -> _state.update {
                it.copy(isQuickDictionaryPinned = !it.isQuickDictionaryPinned)
            }

            is MainIntent.SearchImages -> scope.launch { handleSearchImages(intent) }

            is MainIntent.ShowImageSearch -> scope.launch {
                // Same reason as the dictionary popup: seed the term so the field is filled on
                // the first render rather than a frame later.
                _state.update {
                    it.copy(
                        isImageSearchVisible = true,
                        isImageSearchPinned =
                            if (it.isImageSearchVisible) it.isImageSearchPinned else false,
                        imageSearchTerm = intent.selectedText.ifBlank { it.imageSearchTerm },
                        isImageSearchLoading = intent.selectedText.isNotBlank(),
                        imageSearchTriggerCount = it.imageSearchTriggerCount + 1
                    )
                }
                if (intent.selectedText.isNotBlank()) {
                    handleSearchImages(MainIntent.SearchImages(intent.selectedText, intent.language))
                }
            }

            is MainIntent.HideImageSearch -> _state.update {
                it.copy(isImageSearchVisible = false, isImageSearchPinned = false)
            }

            is MainIntent.ToggleImageSearchPin -> _state.update {
                it.copy(isImageSearchPinned = !it.isImageSearchPinned)
            }

            is MainIntent.UpdateInlineDefinition ->
                if (intent.word.isBlank()) {
                    fetchInlineDefinitionUseCase.clear { transform -> _state.update(transform) }
                } else {
                    fetchInlineDefinitionUseCase(
                        word = intent.word,
                        language = intent.language,
                        alternateWord = intent.alternateWord,
                        alternateLanguage = intent.alternateLanguage,
                        updateState = { transform -> _state.update(transform) }
                    )
                }
        }
    }

    // -------------------------------------------------------------------------
    // Async handlers
    // -------------------------------------------------------------------------

    private fun startDocumentTranslation(intent: MainIntent.TranslateDocument) {
        documentTranslationJob?.cancel()
        val generation = ++documentTranslationGeneration
        documentTranslationJob = scope.launch {
            val state = _state.value
            try {
                val output = documentTranslationUseCase(
                    DocumentTranslationRequest(
                        inputFile = intent.inputFile,
                        outputFile = intent.outputFile,
                        sourceLanguage = state.sourceLanguage,
                        targetLanguage = state.targetLanguage,
                        pdfMode = intent.pdfMode
                    )
                ) { progress ->
                    if (generation == documentTranslationGeneration) {
                        _state.update { it.copy(documentTranslationProgress = progress) }
                    }
                }
                if (generation != documentTranslationGeneration) return@launch
                _state.update { it.copy(documentTranslationProgress = null) }
                _eventChannel.send(MainEvent.DocumentTranslationCompleted(output))
            } catch (error: CancellationException) {
                if (generation == documentTranslationGeneration) {
                    _state.update { it.copy(documentTranslationProgress = null) }
                }
                throw error
            } catch (error: DocumentTranslationException) {
                if (generation != documentTranslationGeneration) return@launch
                _state.update { it.copy(documentTranslationProgress = null) }
                _eventChannel.send(MainEvent.DocumentTranslationFailed(error.message))
            } catch (error: Exception) {
                if (generation != documentTranslationGeneration) return@launch
                _state.update { it.copy(documentTranslationProgress = null) }
                _eventChannel.send(
                    MainEvent.DocumentTranslationFailed(error.message ?: "Document translation failed.")
                )
            } finally {
                if (generation == documentTranslationGeneration) {
                    documentTranslationJob = null
                }
            }
        }
    }

    private fun cancelDocumentTranslation() {
        documentTranslationGeneration++
        documentTranslationJob?.cancel(CancellationException("Document translation cancelled"))
        documentTranslationJob = null
        _state.update { it.copy(documentTranslationProgress = null) }
    }

    private suspend fun handleOcrAndTranslate(intent: MainIntent.OcrAndTranslateImage) {
        val extractedText = ocrAndTranslateUseCase(
            image = intent.image,
            currentState = _state.value,
            onStatusUpdate = ::updateStatusBar
        )

        if (extractedText.isBlank()) return

        // Область снимка часто уже показывает ответ собеседника на языке, в который окно
        // настроено переводить (например, RU auto → EN, а в кадре — английский текст
        // собеседника). Без этой подсказки перевод определяет исходный EN, видит, что цель
        // тоже EN, и молча возвращает тот же текст. sameLanguageFallbackTarget — тот же приём,
        // что уже используется для перевода при наборе текста: тогда самостоятельно
        // возвращается язык модели.
        val modelLanguage = runCatching { LanguageCode(settingsState.value.modelLanguage) }
            .getOrDefault(LanguageCode.RUSSIAN)

        // Write extracted text into input then translate — same path as manual typing.
        _state.update { it.copy(inputText = extractedText) }
        translateText(sameLanguageFallbackTarget = modelLanguage)
    }

    /**
     * OCR-only path: extracts text from [intent.image] and emits [MainEvent.CopyToClipboard]
     * so the UI layer can write it to the system clipboard.
     * Does NOT translate — the user chose "Copy Text", not "Translate".
     */
    private suspend fun handleOcrAndCopyText(intent: MainIntent.OcrAndCopyText) {
        val extractedText = ocrAndTranslateUseCase(
            image = intent.image,
            currentState = _state.value,
            onStatusUpdate = ::updateStatusBar
        )
        if (extractedText.isBlank()) return
        _eventChannel.send(MainEvent.CopyToClipboard(extractedText))
        updateStatusBar(StatusCode.OcrTextCopied, NotificationType.INFO, true)
    }

    private suspend fun handleShowQuickTranslate(intent: MainIntent.ShowQuickTranslate) {
        if (intent.selectedText.isBlank()) return

        if (_state.value.isQuickTranslateDialogVisible) {
            // Already open, pinned or not: replace the text and count the trigger, so the popup
            // refreshes in place and restarts its countdown. Hiding and re-showing it would
            // flicker, move it, and throw away a pin the user had set.
            _state.update {
                it.copy(
                    inputText = intent.selectedText,
                    isQuickTranslateDialogPassive = false,
                    quickTranslateSourceLanguageOverride = null,
                    quickTranslateTargetLanguageOverride = null,
                    quickTranslateDetectedLanguageOverride = null,
                    quickTranslateTriggerCount = it.quickTranslateTriggerCount + 1
                )
            }
        } else {
            // Open a fresh popup — never pinned, whatever the last one was left as.
            //
            // isLoading and the cleared text are set here rather than left to the use case that
            // follows. The popup withholds itself until there is something to show, and it decides
            // that from this state; without it the popup saw "not loading, no text" for the moment
            // between being asked for and the request starting, took that for a finished result,
            // and opened empty — which is the loading state the user was seeing inside the popup
            // instead of the marker that should have covered the wait.
            _state.update {
                it.copy(
                    inputText = intent.selectedText,
                    translatedText = "",
                    isLoading = true,
                    isQuickTranslateDialogPinned = false,
                    isQuickTranslateDialogPassive = false,
                    quickTranslateSourceLanguageOverride = null,
                    quickTranslateTargetLanguageOverride = null,
                    quickTranslateDetectedLanguageOverride = null,
                    isQuickTranslateDialogVisible = true,
                    quickTranslateTriggerCount = it.quickTranslateTriggerCount + 1
                )
            }
        }

        // Let TranslateTextUseCase own isLoading — it sets it at the start of the job.
        translateText()
    }

    private suspend fun translateText(
        textOverride: String? = null,
        sameLanguageFallbackTarget: LanguageCode? = null
    ) {
        translateTextUseCase(
            getState    = { _state.value },
            updateState = { transform -> _state.update(transform) },
            onStatusUpdate = ::updateStatusBar,
            textOverride = textOverride,
            sameLanguageFallbackTarget = sameLanguageFallbackTarget
        )
    }

    /**
     * Recomputes the extra panel alone, falling back to a full translation when there is no
     * translation yet to derive one from.
     */
    private suspend fun refreshExtraOutput() {
        val refreshed = translateTextUseCase.refreshExtraOutput(
            getState = { _state.value },
            updateState = { transform -> _state.update(transform) },
            onStatusUpdate = ::updateStatusBar
        )
        if (!refreshed) translateText()
    }

    private suspend fun handleLookupWord(intent: MainIntent.LookupWord) {
        lookupWordUseCase(
            word = intent.word,
            language = intent.language,
            targetLanguage = _state.value.targetLanguage,
            updateState = { transform -> _state.update(transform) },
            onStatusUpdate = ::updateStatusBar
        )
    }

    private suspend fun handleSearchImages(intent: MainIntent.SearchImages) {
        searchImagesUseCase(
            term = intent.term,
            language = intent.language,
            updateState = { transform -> _state.update(transform) },
            onStatusUpdate = ::updateStatusBar
        )
    }

    private suspend fun handleListen(
        textSource: TextSource,
        textOverride: String?,
        languageOverride: LanguageCode?
    ) {
        handleTextToSpeechUseCase(
            currentState     = _state.value,
            textSource       = textSource,
            textOverride     = textOverride,
            languageOverride = languageOverride,
            onStatusUpdate   = ::updateStatusBar
        )
    }

    private suspend fun handleSpellCheck(text: String, isEnabled: Boolean) {
        val corrections = if (isEnabled && text.isNotBlank()) {
            performSpellCheckUseCase(
                currentState   = _state.value,
                text           = text,
                onStatusUpdate = ::updateStatusBar
            )
        } else {
            emptyList()
        }
        _state.update { it.copy(spellCheckCorrections = corrections) }
    }

    // -------------------------------------------------------------------------
    // Synchronous handlers
    // -------------------------------------------------------------------------

    private fun swapLanguages(context: SwapLanguagesContext) {
        swapLanguagesUseCase(
            currentState     = _state.value,
            context          = context,
            onStateUpdate    = { newState -> _state.value = newState },
            onTranslateNeeded = { dispatch(MainIntent.Translate()) }
        )
    }

    private fun handleUndo() {
        val current = _state.value
        if (!current.canUndo) return

        val newIndex = current.historyIndex - 1
        val snapshot = current.history[newIndex]

        _state.update {
            it.copy(
                inputText              = snapshot.inputText,
                translatedText         = snapshot.translatedText,
                sourceLanguage         = LanguageCode(snapshot.sourceLanguage),
                targetLanguage         = LanguageCode(snapshot.targetLanguage),
                historyIndex           = newIndex,
                isLoading              = false,
                extraOutputText        = snapshot.extraOutputText,
                detectedSourceLanguage = snapshot.detectedSourceLanguage?.let { tag -> LanguageCode(tag) },
                spellCheckCorrections  = emptyList()
            )
        }
    }

    /**
     * Moves forward in history. If [historyIndex] is already at the end of [MainState.history],
     * moving "forward" clears the screen — the implicit state beyond the last snapshot is blank.
     * This allows the user to return to an empty input after browsing history.
     */
    private fun handleRedo() {
        val current = _state.value
        if (!current.canRedo) return

        val newIndex = current.historyIndex + 1

        if (newIndex == current.history.size) {
            // Past the last snapshot — restore blank state.
            _state.update {
                it.copy(
                    inputText              = "",
                    translatedText         = "",
                    extraOutputText        = "",
                    detectedSourceLanguage = null,
                    spellCheckCorrections  = emptyList(),
                    historyIndex           = newIndex
                )
            }
        } else {
            val snapshot = current.history[newIndex]
            _state.update {
                it.copy(
                    inputText              = snapshot.inputText,
                    translatedText         = snapshot.translatedText,
                    sourceLanguage         = LanguageCode(snapshot.sourceLanguage),
                    targetLanguage         = LanguageCode(snapshot.targetLanguage),
                    historyIndex           = newIndex,
                    isLoading              = false,
                    extraOutputText        = snapshot.extraOutputText,
                    detectedSourceLanguage = snapshot.detectedSourceLanguage?.let { tag -> LanguageCode(tag) },
                    spellCheckCorrections  = emptyList()
                )
            }
        }
    }

    private fun handleRestoreHistoryEntry(intent: MainIntent.RestoreHistoryEntry) {
        val snapshot = intent.snapshot
        val idx = _state.value.history.indexOf(snapshot)
        _state.update {
            it.copy(
                inputText              = snapshot.inputText,
                translatedText         = snapshot.translatedText,
                sourceLanguage         = LanguageCode(snapshot.sourceLanguage),
                targetLanguage         = LanguageCode(snapshot.targetLanguage),
                historyIndex           = if (idx >= 0) idx + 1 else it.historyIndex,
                isLoading              = false,
                extraOutputText        = snapshot.extraOutputText,
                detectedSourceLanguage = snapshot.detectedSourceLanguage?.let { tag -> LanguageCode(tag) },
                spellCheckCorrections  = emptyList()
            )
        }
    }

    private suspend fun handleClearHistory() {
        historyRepository.clearHistory()
        _state.update { it.copy(history = emptyList(), historyIndex = 0) }
    }

    /**
     * Регистрирует фоновый запрос атомарно до запуска coroutine. AUTO не принимается, пока
     * выполняется явный Shift, а вытесненный запрос теряет и Job, и право публикации результата.
     */
    private fun startSelectionTranslation(
        selectedText: String,
        capturedAtMillis: Long,
        requestId: Long,
        trigger: SelectionTranslationTrigger,
        interactionGeneration: Long? = null
    ) {
        var supersededJob: Job? = null
        var supersededLane: TranslationLane? = null
        var newJob: Job? = null

        synchronized(selectionRequestLock) {
            when (val admission = selectionTranslationCoordinator.begin(trigger, requestId)) {
                is SelectionTranslationAdmission.Rejected -> return
                is SelectionTranslationAdmission.Accepted -> {
                    admission.superseded?.let { superseded ->
                        supersededJob = selectionTranslationJobs.remove(superseded)
                        supersededLane = superseded.trigger.translationLane
                    }
                    val ticket = admission.ticket
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            handleSelectionTranslation(
                                selectedTextRaw = selectedText,
                                capturedAtMillis = capturedAtMillis,
                                ticket = ticket,
                                interactionGeneration = interactionGeneration
                            )
                        } finally {
                            val completedCurrent = synchronized(selectionRequestLock) {
                                selectionTranslationJobs.remove(ticket)
                                selectionTranslationCoordinator.complete(ticket)
                            }
                            if (ticket.trigger == SelectionTranslationTrigger.AUTO_SELECTION &&
                                completedCurrent
                            ) {
                                _eventChannel.send(MainEvent.AutoSelectionTranslationFinished)
                            }
                        }
                    }
                    selectionTranslationJobs[ticket] = job
                    newJob = job
                }
            }
        }

        supersededJob?.cancel(CancellationException("Selection request superseded"))
        supersededLane
            ?.takeIf { it != trigger.translationLane }
            ?.let { lane ->
                translateTextUseCase.cancel(lane)
        }
        newJob?.start()
    }

    private fun cancelSelectionTranslations() {
        val jobs = synchronized(selectionRequestLock) {
            selectionTranslationCoordinator.cancelAll()
            translateTextUseCase.cancel(TranslationLane.SELECTION_EXPLICIT)
            translateTextUseCase.cancel(TranslationLane.SELECTION_AUTO)
            selectionTranslationJobs.values.toList().also { selectionTranslationJobs.clear() }
        }
        jobs.forEach { it.cancel(CancellationException("Selection translations cancelled")) }
    }

    private suspend fun handleReplaceWithTranslation(selectedText: String) {
        if (selectedText.isBlank()) return
        // isReplacingSelection=true tells the LoadingIndicator observer to show
        // even when the main window is visible. focusableWindowState=false on
        // LoadingIndicator means it never steals focus from the source app.
        _state.update { it.copy(inputText = selectedText, isReplacingSelection = true) }
        translateTextUseCase(
            getState       = { _state.value },
            updateState    = { transform -> _state.update(transform) },
            onStatusUpdate = ::updateStatusBar,
            textOverride   = selectedText,
            lane = TranslationLane.SELECTION_EXPLICIT
        )
        val result = _state.value.translatedText
        _state.update { it.copy(isReplacingSelection = false) }
        if (result.isNotBlank()) {
            _eventChannel.send(MainEvent.PasteTranslation(result))
        }
    }

    /**
     * Переводит текст LIVE-рамки в язык модели на изолированном снимке состояния.
     * Главное окно, его история и индикаторы не меняются.
     */
    private suspend fun handleLiveLensTranslation(intent: MainIntent.TranslateLiveLensText) {
        val sourceText = intent.text.trim()
        if (sourceText.isBlank()) {
            _eventChannel.send(MainEvent.LiveLensTranslationFinished(intent.requestId))
            return
        }

        val modelLanguage = runCatching { LanguageCode(settingsState.value.modelLanguage) }
            .getOrDefault(LanguageCode.RUSSIAN)
        // LIVE-рамка ничего не заменяет, поэтому здесь достаточно отсечь текст, уже написанный
        // на языке модели. AMBIGUOUS (латиница у модели, смешанный текст) обязан переводиться:
        // иначе рамка молчит на всём, что не отличается по письменности.
        if (ShiftSelectionDirectionDetector.detect(sourceText, modelLanguage) ==
            ShiftSelectionDirection.MODEL_LANGUAGE
        ) {
            _eventChannel.send(MainEvent.LiveLensTranslationFinished(intent.requestId))
            return
        }

        var backgroundState = _state.value.copy(
            inputText = sourceText,
            translatedText = "",
            extraOutputText = "",
            sourceLanguage = LanguageCode.AUTO,
            detectedSourceLanguage = null,
            targetLanguage = modelLanguage,
            isLoading = false,
            isExtraOutputLoading = false
        )
        when (val result = translateTextUseCase(
            getState = { backgroundState },
            updateState = { transform -> backgroundState = backgroundState.transform() },
            onStatusUpdate = { _, _, _ -> },
            textOverride = sourceText,
            includeExtraOutput = false,
            applyTranslationRules = false,
            lane = TranslationLane.LIVE_LENS,
            requestContext = TranslationRequestContext(
                requestId = intent.requestId,
                origin = "live_lens"
            )
        )) {
            is TranslationRunResult.Success -> {
                if (result.translatedText.isBlank() ||
                    result.translatedText.trim().equals(sourceText, ignoreCase = true)
                ) {
                    _eventChannel.send(MainEvent.LiveLensTranslationFinished(intent.requestId))
                } else {
                    _eventChannel.send(
                        MainEvent.LiveLensTranslationCompleted(
                            requestId = intent.requestId,
                            sourceText = sourceText,
                            translatedText = result.translatedText,
                            translatorName = result.translatorName
                        )
                    )
                }
            }
            is TranslationRunResult.Failure ->
                _eventChannel.send(
                    MainEvent.LiveLensTranslationFinished(intent.requestId, result.kind)
                )
        }
    }

    /**
     * Выполняет двунаправленный Shift-сценарий без показа главного окна.
     *
     * Перевод идёт на отдельном снимке состояния. Поэтому промежуточные isLoading/inputText
     * не попадают в главный экран, а результат публикуется только после успешного ответа.
     */
    private suspend fun handleSelectionTranslation(
        selectedTextRaw: String,
        capturedAtMillis: Long,
        ticket: SelectionTranslationTicket,
        interactionGeneration: Long?
    ) {
        val trigger = ticket.trigger
        val selectedText = selectedTextRaw.trim()
        if (selectedText.isBlank()) {
            reportSelectionTranslationFailure(
                trigger,
                SelectionTranslationFailureReason.CAPTURE,
                ticket.requestId
            )
            return
        }

        val config = settingsState.value
        val mode = config.shiftTapTranslationMode
        val triggerEnabled = when (trigger) {
            SelectionTranslationTrigger.SHIFT ->
                config.isShiftTapTranslateEnabled && mode != ShiftTapTranslationMode.DISABLED
            SelectionTranslationTrigger.AUTO_SELECTION -> config.isAutoSelectionTranslateEnabled
            SelectionTranslationTrigger.MANUAL_BUTTON,
            SelectionTranslationTrigger.MANUAL_REPLACE_BUTTON -> true
        }
        if (!triggerEnabled) {
            reportSelectionTranslationFailure(
                trigger,
                SelectionTranslationFailureReason.DISABLED,
                ticket.requestId
            )
            return
        }

        val modelLanguage = runCatching { LanguageCode(config.modelLanguage) }
            .getOrDefault(LanguageCode.RUSSIAN)
        val direction = ShiftSelectionDirectionDetector.detect(selectedText, modelLanguage)
        val action = resolveSelectionTranslationAction(trigger, mode, direction)
        if (action == SelectionTranslationAction.IGNORE) {
            if (trigger == SelectionTranslationTrigger.SHIFT) {
                reportSelectionTranslationFailure(
                    trigger,
                    SelectionTranslationFailureReason.UNSUPPORTED_DIRECTION,
                    ticket.requestId
                )
            }
            return
        }
        val shouldReplace = action == SelectionTranslationAction.REPLACE
        // Только явный исходящий Shift получает локальную best-effort коррекцию. Auto-selection,
        // mini-button, иностранный текст, имена и неизвестные слова остаются без изменений.
        val translationInput = if (
            trigger == SelectionTranslationTrigger.SHIFT ||
            trigger == SelectionTranslationTrigger.MANUAL_REPLACE_BUTTON
        ) {
            prepareShiftTranslationInput(selectedText, modelLanguage, direction)
        } else {
            selectedText
        }

        val baseState = _state.value
        // AUTO нужен и для исходящего текста: ответ провайдера сообщает точный ru/uk, который
        // запоминается для обратного перевода именно этого результата.
        val sourceLanguage = LanguageCode.AUTO
        val targetLanguage = if (direction == ShiftSelectionDirection.MODEL_LANGUAGE) {
            resolveShiftOutboundTarget(baseState, config, modelLanguage) ?: run {
                reportSelectionTranslationFailure(
                    trigger,
                    SelectionTranslationFailureReason.NO_TARGET_LANGUAGE,
                    ticket.requestId
                )
                return
            }
        } else {
            shiftTranslationMemory.reverseTargetFor(selectedText, modelLanguage)
        }

        val wasRecentlyShown = !shouldReplace && shiftTranslationMemory.wasRecentlyShown(
            sourceText = selectedText,
            targetLanguage = targetLanguage,
            nowMillis = System.currentTimeMillis(),
            windowMillis = PASSIVE_OVERLAY_DEDUPLICATION_MS
        )
        // Дедупликация нужна только двум автоматическим mouse-selection событиям. Явный Shift
        // всегда является новой командой пользователя и не должен молча поглощаться результатом,
        // который auto-selection успел показать непосредственно перед нажатием.
        if (shouldSuppressRecentPassiveOverlay(trigger, wasRecentlyShown)) return

        var backgroundState = baseState.copy(
            inputText = translationInput,
            translatedText = "",
            extraOutputText = "",
            sourceLanguage = sourceLanguage,
            detectedSourceLanguage = null,
            targetLanguage = targetLanguage,
            isLoading = false,
            isExtraOutputLoading = false,
            isQuickTranslateDialogVisible = false,
            isQuickTranslateDialogPassive = false
        )

        val expiresAt = capturedAtMillis + SHIFT_REPLACE_VALIDITY_MS
        var rejectionReason: SelectionTranslationFailureReason? = null
        val execution = executeSelectionTranslation(
            translationInput = translationInput,
            action = action,
            translate = { input ->
                when (val result = translateTextUseCase(
                    getState = { backgroundState },
                    updateState = { transform -> backgroundState = backgroundState.transform() },
                    // Фоновый Shift не должен менять статус скрытого главного окна.
                    onStatusUpdate = { _, _, _ -> },
                    textOverride = input,
                    // Summary/rewrite относятся к главному окну и не должны задерживать замену.
                    includeExtraOutput = false,
                    // Входящий Shift обязан переводить в язык модели независимо от правил окна.
                    applyTranslationRules = false,
                    lane = trigger.translationLane,
                    requestContext = TranslationRequestContext(
                        requestId = ticket.requestId,
                        origin = trigger.telemetryOrigin
                    )
                )) {
                    is TranslationRunResult.Success ->
                        SelectionTranslationAttempt.Translated(result.translatedText)
                    is TranslationRunResult.Failure ->
                        SelectionTranslationAttempt.Failed(result.kind.toSelectionFailureReason())
                }
            },
            canDeliver = {
                if (!selectionTranslationCoordinator.isCurrent(ticket)) {
                    rejectionReason = SelectionTranslationFailureReason.CANCELLED
                    return@executeSelectionTranslation false
                }
                val currentConfig = settingsState.value
                val stillEnabled = when (trigger) {
                    SelectionTranslationTrigger.SHIFT ->
                        currentConfig.isShiftTapTranslateEnabled &&
                            resolveSelectionTranslationAction(
                                trigger,
                                currentConfig.shiftTapTranslationMode,
                                direction
                            ) == action
                    SelectionTranslationTrigger.AUTO_SELECTION ->
                        currentConfig.isAutoSelectionTranslateEnabled
                    SelectionTranslationTrigger.MANUAL_BUTTON -> true
                    SelectionTranslationTrigger.MANUAL_REPLACE_BUTTON -> true
                }
                if (!stillEnabled) {
                    rejectionReason = SelectionTranslationFailureReason.DISABLED
                    return@executeSelectionTranslation false
                }
                if (shouldReplace && System.currentTimeMillis() > expiresAt) {
                    rejectionReason = SelectionTranslationFailureReason.CANCELLED
                    return@executeSelectionTranslation false
                }
                true
            },
            onReplace = { translatedText ->
                publishShiftHistory(backgroundState)
                // Память исходного RU/UK нужна только после исходящего перевода. Обратный
                // EN→RU не должен перезаписать её определённым английским языком.
                if (direction == ShiftSelectionDirection.MODEL_LANGUAGE) {
                    shiftTranslationMemory.rememberReplacement(
                        translatedText = translatedText,
                        detectedSourceLanguage = backgroundState.detectedSourceLanguage ?: modelLanguage,
                        configuredModelLanguage = modelLanguage
                    )
                }
                _eventChannel.send(
                    MainEvent.PasteTranslation(
                        translatedText = restoreSelectionBoundaryWhitespace(
                            originalText = selectedTextRaw,
                            translatedText = translatedText
                        ),
                        expiresAtMillis = expiresAt,
                        showShiftFeedback = true,
                        requestId = ticket.requestId,
                        interactionGeneration = interactionGeneration
                    )
                )
            },
            onPassiveOverlay = { translatedText ->
                publishShiftHistory(backgroundState)
                _state.update {
                    it.copy(
                        inputText = selectedText,
                        translatedText = translatedText,
                        extraOutputText = "",
                        isLoading = false,
                        isExtraOutputLoading = false,
                        isQuickTranslateDialogPinned = false,
                        isQuickTranslateDialogPassive = true,
                        quickTranslateSourceLanguageOverride = sourceLanguage,
                        quickTranslateTargetLanguageOverride = targetLanguage,
                        quickTranslateDetectedLanguageOverride = backgroundState.detectedSourceLanguage,
                        isQuickTranslateDialogVisible = true,
                        quickTranslateTriggerCount = it.quickTranslateTriggerCount + 1
                    )
                }
                shiftTranslationMemory.rememberPassiveOverlay(
                    sourceText = selectedText,
                    targetLanguage = targetLanguage,
                    nowMillis = System.currentTimeMillis()
                )
            }
        )

        when (execution) {
            SelectionTranslationExecution.DELIVERED,
            SelectionTranslationExecution.IGNORED -> Unit
            is SelectionTranslationExecution.FAILED ->
                reportSelectionTranslationFailure(
                    trigger,
                    execution.reason,
                    ticket.requestId
                )
            SelectionTranslationExecution.REJECTED -> rejectionReason?.let { reason ->
                reportSelectionTranslationFailure(trigger, reason, ticket.requestId)
            }
        }
    }

    /** История фонового use case публикуется только вместе с реально доставленным результатом. */
    private fun publishShiftHistory(backgroundState: MainState) {
        _state.update {
            it.copy(
                history = backgroundState.history,
                historyIndex = backgroundState.historyIndex
            )
        }
    }

    private suspend fun reportSelectionTranslationFailure(
        trigger: SelectionTranslationTrigger,
        reason: SelectionTranslationFailureReason,
        requestId: Long
    ) {
        if (trigger != SelectionTranslationTrigger.AUTO_SELECTION &&
            reason != SelectionTranslationFailureReason.CANCELLED
        ) {
            _eventChannel.send(MainEvent.ShiftTranslationFailed(reason, requestId))
        }
    }

    /** Выбранный иностранный target имеет приоритет; русский не переводится сам в себя. */
    private fun resolveShiftOutboundTarget(
        state: MainState,
        config: Configuration,
        modelLanguage: LanguageCode
    ): LanguageCode? {
        val preferred = runCatching { LanguageCode(config.preferredTargetLanguage) }.getOrNull()
        return sequenceOf(state.targetLanguage, preferred, LanguageCode.ENGLISH)
            .filterNotNull()
            .plus(state.availableLanguages.asSequence())
            .firstOrNull { it != LanguageCode.AUTO && it != modelLanguage }
    }

    private fun handleCycleTargetLanguage() {
        val languages = _state.value.availableLanguages.filter { it != LanguageCode.AUTO }
        if (languages.isEmpty()) return
        val current = _state.value.targetLanguage
        val currentIdx = languages.indexOf(current)
        val nextIdx = (currentIdx + 1) % languages.size
        _state.update { it.copy(targetLanguage = languages[nextIdx]) }
    }

    private companion object {
        /** После этого срока нельзя гарантировать, что исходное выделение всё ещё активно. */
        /** Покрывает общий 30-секундный network budget и небольшой запас на безопасную вставку. */
        const val SHIFT_REPLACE_VALIDITY_MS = 35_000L
        const val PASSIVE_OVERLAY_DEDUPLICATION_MS = 1_500L
    }

    suspend fun onShutdown() {
        cancelDocumentTranslation()
        if (settingsState.value.clearHistoryOnExit) {
            historyRepository.clearHistory()
        }
        handleTextToSpeechUseCase.shutdown()
    }

    private suspend fun updateStatusBar(
        code: StatusCode,
        type: NotificationType,
        isTemporary: Boolean
    ) {
        _eventChannel.send(MainEvent.UpdateStatusBar(code, type, isTemporary))
    }
}

internal enum class SelectionTranslationTrigger {
    SHIFT,
    AUTO_SELECTION,
    MANUAL_BUTTON,
    MANUAL_REPLACE_BUTTON
}

private val SelectionTranslationTrigger.translationLane: TranslationLane
    get() = when (this) {
        SelectionTranslationTrigger.SHIFT,
        SelectionTranslationTrigger.MANUAL_BUTTON,
        SelectionTranslationTrigger.MANUAL_REPLACE_BUTTON -> TranslationLane.SELECTION_EXPLICIT
        SelectionTranslationTrigger.AUTO_SELECTION -> TranslationLane.SELECTION_AUTO
    }

private val SelectionTranslationTrigger.telemetryOrigin: String
    get() = when (this) {
        SelectionTranslationTrigger.SHIFT -> "shift"
        SelectionTranslationTrigger.AUTO_SELECTION -> "auto_selection"
        SelectionTranslationTrigger.MANUAL_BUTTON -> "selection_button"
        SelectionTranslationTrigger.MANUAL_REPLACE_BUTTON -> "selection_replace_button"
    }

internal fun TranslationFailureKind.toSelectionFailureReason(): SelectionTranslationFailureReason =
    when (this) {
        TranslationFailureKind.NETWORK -> SelectionTranslationFailureReason.NETWORK
        TranslationFailureKind.RATE_LIMIT -> SelectionTranslationFailureReason.RATE_LIMIT
        TranslationFailureKind.TIMEOUT -> SelectionTranslationFailureReason.TIMEOUT
        TranslationFailureKind.AUTHENTICATION -> SelectionTranslationFailureReason.AUTHENTICATION
        TranslationFailureKind.INVALID -> SelectionTranslationFailureReason.INVALID
        TranslationFailureKind.SERVICE_UNAVAILABLE ->
            SelectionTranslationFailureReason.SERVICE_UNAVAILABLE
        TranslationFailureKind.CANCELLED -> SelectionTranslationFailureReason.CANCELLED
        TranslationFailureKind.UNKNOWN -> SelectionTranslationFailureReason.UNKNOWN
    }

/** Явная команда Shift никогда не подавляется автоматическим результатом того же выделения. */
internal fun shouldSuppressRecentPassiveOverlay(
    trigger: SelectionTranslationTrigger,
    wasRecentlyShown: Boolean
): Boolean = trigger == SelectionTranslationTrigger.AUTO_SELECTION && wasRecentlyShown

/** Только последний автоматический запрос имеет право убрать общий индикатор возле курсора. */
internal fun shouldDismissAutomaticSelectionProgress(
    requestGeneration: Long,
    currentGeneration: Long
): Boolean = requestGeneration == currentGeneration
