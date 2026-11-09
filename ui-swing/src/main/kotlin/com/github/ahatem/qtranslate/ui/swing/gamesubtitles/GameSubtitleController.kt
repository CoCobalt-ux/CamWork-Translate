package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationFailureKind
import com.github.ahatem.qtranslate.core.main.mvi.MainEvent
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.ui.swing.livelens.LiveLensFrameCapture
import com.github.ahatem.qtranslate.ui.swing.livelens.defaultLiveLensFrameCapture
import java.awt.Rectangle
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PendingGameSubtitle(
    val sourceText: String,
    val replaceLatest: Boolean,
    val retryCount: Int = 0,
    val retryAtMillis: Long = 0L
)

/**
 * Очередь не копит промежуточные версии одной постепенно дописываемой реплики.
 * Первый вариант может уже переводиться, но в ожидании остаётся только самый свежий.
 */
internal class GameSubtitlePendingQueue(
    private val maxSize: Int = DEFAULT_MAX_PENDING_SUBTITLES
) {
    private val items = ArrayDeque<PendingGameSubtitle>()

    val size: Int
        get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    fun isNotEmpty(): Boolean = items.isNotEmpty()

    fun clear() = items.clear()

    fun containsSource(sourceText: String): Boolean {
        val key = gameSubtitleCacheKey(sourceText)
        return items.any { queued -> gameSubtitleCacheKey(queued.sourceText) == key }
    }

    fun offer(subtitle: PendingGameSubtitle) {
        if (subtitle.sourceText.isBlank() || containsSource(subtitle.sourceText)) return
        if (subtitle.retryCount == 0) items.removeIf { it.retryCount > 0 }
        if (subtitle.replaceLatest && items.isNotEmpty()) items.removeLast()
        while (items.size >= maxSize.coerceAtLeast(1)) items.removeFirst()
        items.addLast(subtitle.copy(sourceText = subtitle.sourceText.trim()))
    }

    fun removeFirst(): PendingGameSubtitle = items.removeFirst()

    fun snapshot(): List<PendingGameSubtitle> = items.toList()

    private companion object {
        const val DEFAULT_MAX_PENDING_SUBTITLES = 5
    }
}

internal fun gameSubtitleCacheKey(value: String): String =
    normalizeGameSubtitleText(value).lowercase(Locale.ROOT)

/** Обратные вызовы отделены от окна, чтобы проверять восстановление без рабочего стола. */
internal data class GameSubtitleActions(
    val togglePause: () -> Unit,
    val changeArea: () -> Unit,
    val close: () -> Unit
)

/** Непрерывный OCR одной игровой области с восстановлением после сбоев и отмены. */
internal class GameSubtitleController(
    private val scope: CoroutineScope,
    private val strings: GameSubtitleStrings,
    private val dispatch: (MainIntent) -> Unit,
    private val logger: Logger,
    private val onChangeArea: () -> Unit,
    private val frameCapture: LiveLensFrameCapture? = defaultLiveLensFrameCapture(),
    private val windowFactory: (GameSubtitleActions) -> GameSubtitleView = { actions ->
        GameSubtitleWindow(strings, actions.togglePause, actions.changeArea, actions.close)
    },
    private val uiExecutor: (() -> Unit) -> Unit = { action ->
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    },
    private val pollingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val requestTimeoutMillis: Long = 35_000L
) {
    private val sequence = AtomicLong(REQUEST_ID_BASE)
    private val captureMutex = Mutex()
    private val tracker = GameSubtitleFrameTracker()
    private val translationCache = object : LinkedHashMap<String, String>(CACHE_SIZE + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_SIZE
    }
    private val window by lazy { windowFactory(GameSubtitleActions(::togglePause, ::changeArea, ::close)) }
    private var captureBounds = Rectangle()
    private var pollJob: Job? = null
    private var frameRequestId: Long? = null
    private var frameRequestImage: ImageData? = null
    private var frameRequestStartedAt = 0L
    private var translationRequestId: Long? = null
    private var translationRequestStartedAt = 0L
    private var activeSubtitle: PendingGameSubtitle? = null
    private val pendingSubtitles = GameSubtitlePendingQueue()
    private var paused = false
    private var running = false
    private var sessionGeneration = 0L
    private var captureInProgress = false
    private var recognizedFrame: ImageData? = null
    private var recognizedText = ""
    private var recognitionFailures = 0
    private var recognitionRetryAt = 0L

    fun open(bounds: Rectangle) {
        stopWork()
        synchronized(this) {
            captureBounds = Rectangle(bounds)
            tracker.reset()
            translationCache.clear()
            resetRecognition()
            paused = false
            running = true
        }
        runOnUi {
            window.resetTranslations()
            window.showAt(Rectangle(bounds))
            startPolling()
        }
        logger.info("LIVE-субтитры запущены: область=${bounds.x},${bounds.y} ${bounds.width}x${bounds.height}")
    }

    fun handle(event: MainEvent) {
        when (event) {
            is MainEvent.LiveLensFrameRecognized -> completeRecognition(event.requestId, event.text)
            is MainEvent.LiveLensFrameRecognitionFinished -> completeRecognition(event.requestId, null)
            is MainEvent.LiveLensTranslationCompleted -> completeTranslation(
                event.requestId, event.translatedText, event.translatorName, null
            )
            is MainEvent.LiveLensTranslationFinished -> completeTranslation(
                event.requestId, null, "", event.failure
            )
            else -> Unit
        }
    }

    @Synchronized
    private fun startPolling() {
        if (!running || paused) return
        pollJob?.cancel()
        val generation = sessionGeneration
        pollJob = scope.launch(pollingDispatcher) {
            while (isActive) {
                try {
                    requestFrameIfIdle(generation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger.error("Сбой цикла LIVE-субтитров; следующая попытка продолжит работу", error)
                    runOnUi { window.setMode(GameSubtitleWindowMode.ERROR) }
                }
                delay(CAPTURE_INTERVAL_MS)
            }
        }
        runOnUi { window.setMode(GameSubtitleWindowMode.SCANNING) }
    }

    private suspend fun requestFrameIfIdle(generation: Long) {
        val bounds = synchronized(this) {
            if (!running || paused || sessionGeneration != generation) return
            recoverExpiredRequests()
            submitNextSubtitle()
            if (frameRequestId != null || captureInProgress || nowMillis() < recognitionRetryAt) return
            captureInProgress = true
            Rectangle(captureBounds)
        }
        // Захват может ждать EDT. Монитор контроллера здесь свободен для паузы и закрытия.
        val image = try {
            captureMutex.withLock {
                currentCoroutineContext().ensureActive()
                frameCapture?.let { capture -> window.captureWithoutOverlay { capture.capture(bounds) } }
            }
        } finally {
            synchronized(this) {
                if (sessionGeneration == generation) captureInProgress = false
            }
        }
        currentCoroutineContext().ensureActive()
        synchronized(this) {
            if (!running || paused || sessionGeneration != generation) return
            if (image == null) {
                runOnUi { window.setMode(GameSubtitleWindowMode.ERROR) }
                return
            }
            if (image == recognizedFrame) {
                // Повтор успешного пустого OCR продвигает время очистки без нового распознавания.
                acceptRecognition(recognizedText)
                return
            }
            val requestId = sequence.incrementAndGet()
            frameRequestId = requestId
            frameRequestImage = image
            frameRequestStartedAt = nowMillis()
            try {
                dispatch(MainIntent.RecognizeLiveLensFrame(image, requestId))
            } catch (error: Exception) {
                failRecognition()
                throw error
            }
        }
    }

    @Synchronized
    private fun completeRecognition(requestId: Long, text: String?) {
        if (requestId != frameRequestId || !running || paused) return
        if (text == null) {
            failRecognition()
            return
        }
        recognizedFrame = frameRequestImage
        recognizedText = text
        frameRequestId = null
        frameRequestImage = null
        recognitionFailures = 0
        recognitionRetryAt = 0L
        acceptRecognition(text)
    }

    private fun acceptRecognition(text: String) {
        when (val decision = tracker.observe(text, nowMillis())) {
            GameSubtitleFrameDecision.Unchanged -> Unit
            GameSubtitleFrameDecision.Clear -> Unit
            is GameSubtitleFrameDecision.Translate -> {
                val subtitle = PendingGameSubtitle(decision.sourceText, decision.replaceLatest)
                val sourceKey = gameSubtitleCacheKey(subtitle.sourceText)
                if (activeSubtitle?.sourceText?.let(::gameSubtitleCacheKey) != sourceKey) {
                    pendingSubtitles.offer(subtitle)
                }
            }
        }
        submitNextSubtitle()
    }

    private fun failRecognition() {
        frameRequestId = null
        frameRequestImage = null
        recognitionFailures = (recognitionFailures + 1).coerceAtMost(4)
        recognitionRetryAt = nowMillis() + retryDelay(recognitionFailures)
        runOnUi { window.setMode(GameSubtitleWindowMode.ERROR) }
        logger.warn("LIVE-субтитры: распознавание не завершено; запланирована повторная попытка")
    }

    private fun recoverExpiredRequests() {
        val now = nowMillis()
        if (frameRequestId != null && now - frameRequestStartedAt >= requestTimeoutMillis) {
            failRecognition()
        }
        translationRequestId?.takeIf {
            now - translationRequestStartedAt >= requestTimeoutMillis
        }?.let { completeTranslation(it, null, "", TranslationFailureKind.TIMEOUT) }
    }

    @Synchronized
    private fun submitNextSubtitle() {
        if (!running || paused || translationRequestId != null) return
        while (pendingSubtitles.isNotEmpty()) {
            val subtitle = pendingSubtitles.removeFirst()
            if (subtitle.retryAtMillis > nowMillis()) {
                pendingSubtitles.offer(subtitle)
                return
            }
            val source = subtitle.sourceText.trim()
            if (source.isBlank()) continue
            val cached = translationCache[gameSubtitleCacheKey(source)]
            if (cached != null) {
                runOnUi { window.showTranslation(cached, subtitle.replaceLatest) }
                continue
            }
            activeSubtitle = subtitle
            val requestId = sequence.incrementAndGet()
            translationRequestId = requestId
            translationRequestStartedAt = nowMillis()
            runOnUi { window.setMode(GameSubtitleWindowMode.TRANSLATING) }
            try {
                dispatch(MainIntent.TranslateLiveLensText(source, requestId))
            } catch (cancelled: CancellationException) {
                translationRequestId = null
                activeSubtitle = null
                throw cancelled
            } catch (error: Exception) {
                logger.error("Не удалось отправить LIVE-субтитр на перевод", error)
                completeTranslation(requestId, null, "", TranslationFailureKind.UNKNOWN)
            }
            return
        }
        runOnUi { window.setMode(GameSubtitleWindowMode.SCANNING) }
    }

    @Synchronized
    private fun completeTranslation(
        requestId: Long,
        translatedText: String?,
        provider: String,
        failure: TranslationFailureKind?
    ) {
        if (requestId != translationRequestId || !running || paused) return
        val completedSubtitle = activeSubtitle ?: return
        translationRequestId = null
        activeSubtitle = null
        if (!translatedText.isNullOrBlank()) {
            translationCache[gameSubtitleCacheKey(completedSubtitle.sourceText)] = translatedText.trim()
            runOnUi { window.showTranslation(translatedText, completedSubtitle.replaceLatest) }
            logger.info("LIVE-субтитры: переведено через $provider")
        } else if (failure != null) {
            if (pendingSubtitles.isEmpty() && completedSubtitle.retryCount < MAX_TRANSLATION_RETRIES) {
                val retryCount = completedSubtitle.retryCount + 1
                pendingSubtitles.offer(completedSubtitle.copy(
                    retryCount = retryCount,
                    retryAtMillis = nowMillis() + retryDelay(retryCount)
                ))
            }
            if (failure != TranslationFailureKind.CANCELLED) {
                runOnUi { window.setMode(GameSubtitleWindowMode.ERROR) }
                logger.warn("LIVE-субтитры: перевод не выполнен, причина=$failure")
            }
        }
        submitNextSubtitle()
    }

    private fun togglePause() {
        val shouldResume = synchronized(this) {
            if (!running) return
            if (paused) {
                paused = false
                true
            } else {
                paused = true
                sessionGeneration++
                pollJob?.cancel()
                pollJob = null
                frameRequestId = null
                translationRequestId = null
                activeSubtitle = null
                pendingSubtitles.clear()
                tracker.reset()
                resetRecognition()
                false
            }
        }
        if (shouldResume) startPolling() else {
            dispatch(MainIntent.CancelLiveLensTranslation)
            runOnUi { window.setMode(GameSubtitleWindowMode.PAUSED) }
        }
    }

    private fun changeArea() {
        close()
        onChangeArea()
    }

    fun close() {
        stopWork()
        runOnUi { window.dispose() }
    }

    @Synchronized
    private fun stopWork() {
        sessionGeneration++
        running = false
        paused = false
        pollJob?.cancel()
        pollJob = null
        frameRequestId = null
        translationRequestId = null
        activeSubtitle = null
        pendingSubtitles.clear()
        tracker.reset()
        resetRecognition()
        dispatch(MainIntent.CancelLiveLensTranslation)
    }

    private fun resetRecognition() {
        recognizedFrame = null
        recognizedText = ""
        frameRequestImage = null
        recognitionFailures = 0
        recognitionRetryAt = 0L
        captureInProgress = false
    }

    private fun runOnUi(action: () -> Unit) {
        val generation = synchronized(this) { sessionGeneration }
        uiExecutor {
            if (synchronized(this) { generation == sessionGeneration }) action()
        }
    }

    private fun retryDelay(failures: Int): Long =
        (CAPTURE_INTERVAL_MS * (1L shl (failures - 1).coerceIn(0, 5))).coerceAtMost(8_000L)

    private companion object {
        const val CAPTURE_INTERVAL_MS = 650L
        const val CACHE_SIZE = 128
        const val MAX_TRANSLATION_RETRIES = 2
        const val REQUEST_ID_BASE = 8_000_000_000_000L
    }
}
