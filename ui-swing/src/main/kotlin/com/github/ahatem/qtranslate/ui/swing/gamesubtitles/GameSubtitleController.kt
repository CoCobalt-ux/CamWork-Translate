package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationFailureKind
import com.github.ahatem.qtranslate.core.main.mvi.MainEvent
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.ui.swing.livelens.LiveLensFrameCapture
import com.github.ahatem.qtranslate.ui.swing.livelens.defaultLiveLensFrameCapture
import java.awt.Rectangle
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Непрерывный OCR одной игровой области с устойчивым, не меняющим расположение выводом. */
internal class GameSubtitleController(
    private val scope: CoroutineScope,
    private val strings: GameSubtitleStrings,
    private val dispatch: (MainIntent) -> Unit,
    private val logger: Logger,
    private val onChangeArea: () -> Unit,
    private val frameCapture: LiveLensFrameCapture? = defaultLiveLensFrameCapture()
) {
    private val sequence = AtomicLong(REQUEST_ID_BASE)
    private val tracker = GameSubtitleFrameTracker()
    private val translationCache = object : LinkedHashMap<String, String>(CACHE_SIZE + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_SIZE
    }

    private val window by lazy {
        GameSubtitleWindow(
            strings = strings,
            onTogglePause = ::togglePause,
            onChangeArea = ::changeArea,
            onClose = ::close
        )
    }

    private var captureBounds = Rectangle()
    private var pollJob: Job? = null
    private var frameRequestId: Long? = null
    private var translationRequestId: Long? = null
    private var translationSource = ""
    private var latestSource = ""
    private var paused = false

    fun open(bounds: Rectangle) {
        stopWork()
        captureBounds = Rectangle(bounds)
        tracker.reset()
        latestSource = ""
        translationCache.clear()
        paused = false
        runOnUi {
            window.clearTranslation()
            window.showAt(Rectangle(bounds))
        }
        logger.info(
            "LIVE-субтитры запущены: область=${bounds.x},${bounds.y} ${bounds.width}x${bounds.height}, " +
                "интервал=${CAPTURE_INTERVAL_MS}мс"
        )
        startPolling()
    }

    fun handle(event: MainEvent) {
        when (event) {
            is MainEvent.LiveLensFrameRecognized -> completeRecognition(event.requestId, event.text)
            is MainEvent.LiveLensFrameRecognitionFinished -> completeRecognition(event.requestId, null)
            is MainEvent.LiveLensTranslationCompleted -> completeTranslation(
                event.requestId,
                event.translatedText,
                event.translatorName,
                null
            )
            is MainEvent.LiveLensTranslationFinished -> completeTranslation(
                event.requestId,
                null,
                "",
                event.failure
            )
            else -> Unit
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                requestFrameIfIdle()
                delay(CAPTURE_INTERVAL_MS)
            }
        }
        runOnUi { window.setMode(GameSubtitleWindowMode.SCANNING) }
    }

    @Synchronized
    private fun requestFrameIfIdle() {
        if (paused || frameRequestId != null) return
        val capture = frameCapture ?: run {
            runOnUi { window.setMode(GameSubtitleWindowMode.ERROR) }
            return
        }
        val image = window.captureWithoutOverlay { capture.capture(Rectangle(captureBounds)) }
            ?: run {
                runOnUi { window.setMode(GameSubtitleWindowMode.ERROR) }
                return
            }
        val requestId = sequence.incrementAndGet()
        frameRequestId = requestId
        dispatch(MainIntent.RecognizeLiveLensFrame(image, requestId))
    }

    @Synchronized
    private fun completeRecognition(requestId: Long, recognizedText: String?) {
        if (requestId != frameRequestId) return
        frameRequestId = null
        when (val decision = tracker.observe(recognizedText)) {
            GameSubtitleFrameDecision.Unchanged -> Unit
            GameSubtitleFrameDecision.Clear -> {
                latestSource = ""
                runOnUi { window.clearTranslation() }
            }
            is GameSubtitleFrameDecision.Translate -> acceptSource(decision.sourceText)
        }
        if (translationRequestId == null && !paused) {
            runOnUi { window.setMode(GameSubtitleWindowMode.SCANNING) }
        }
    }

    @Synchronized
    private fun acceptSource(sourceText: String) {
        latestSource = sourceText
        val cacheKey = cacheKey(sourceText)
        translationCache[cacheKey]?.let { cached ->
            runOnUi {
                window.showTranslation(cached)
                window.setMode(GameSubtitleWindowMode.SCANNING)
            }
            return
        }
        if (translationRequestId == null) submitLatestSource()
    }

    @Synchronized
    private fun submitLatestSource() {
        val source = latestSource.trim()
        if (source.isBlank() || translationRequestId != null) return
        translationSource = source
        val requestId = sequence.incrementAndGet()
        translationRequestId = requestId
        runOnUi { window.setMode(GameSubtitleWindowMode.TRANSLATING) }
        logger.debug("LIVE-субтитры: перевод id=$requestId, символов=${source.length}")
        dispatch(MainIntent.TranslateLiveLensText(source, requestId))
    }

    @Synchronized
    private fun completeTranslation(
        requestId: Long,
        translatedText: String?,
        provider: String,
        failure: TranslationFailureKind?
    ) {
        if (requestId != translationRequestId) return
        val completedSource = translationSource
        translationRequestId = null
        translationSource = ""

        if (!translatedText.isNullOrBlank()) {
            translationCache[cacheKey(completedSource)] = translatedText.trim()
            if (latestSource.equals(completedSource, ignoreCase = true)) {
                runOnUi {
                    window.showTranslation(translatedText)
                    window.setMode(GameSubtitleWindowMode.SCANNING)
                }
                logger.info("LIVE-субтитры: переведено через $provider")
            }
        } else if (failure != null && failure != TranslationFailureKind.CANCELLED) {
            runOnUi { window.setMode(GameSubtitleWindowMode.ERROR) }
            logger.warn("LIVE-субтитры: перевод не выполнен, причина=$failure")
        } else if (latestSource.equals(completedSource, ignoreCase = true)) {
            runOnUi { window.clearTranslation() }
        }

        if (!latestSource.equals(completedSource, ignoreCase = true)) {
            translationCache[cacheKey(latestSource)]?.let { cached ->
                runOnUi {
                    window.showTranslation(cached)
                    window.setMode(GameSubtitleWindowMode.SCANNING)
                }
            } ?: submitLatestSource()
        } else if (failure == null && !paused) {
            runOnUi { window.setMode(GameSubtitleWindowMode.SCANNING) }
        }
    }

    @Synchronized
    private fun togglePause() {
        if (paused) {
            paused = false
            startPolling()
        } else {
            paused = true
            pollJob?.cancel()
            pollJob = null
            frameRequestId = null
            translationRequestId = null
            translationSource = ""
            dispatch(MainIntent.CancelLiveLensTranslation)
            runOnUi { window.setMode(GameSubtitleWindowMode.PAUSED) }
        }
    }

    private fun changeArea() {
        stopWork()
        runOnUi { window.dispose() }
        onChangeArea()
    }

    fun close() {
        stopWork()
        runOnUi { window.dispose() }
    }

    @Synchronized
    private fun stopWork() {
        pollJob?.cancel()
        pollJob = null
        paused = false
        frameRequestId = null
        translationRequestId = null
        translationSource = ""
        dispatch(MainIntent.CancelLiveLensTranslation)
    }

    private fun cacheKey(value: String): String = normalizeGameSubtitleText(value).lowercase()

    private fun runOnUi(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }

    private companion object {
        const val CAPTURE_INTERVAL_MS = 650L
        const val CACHE_SIZE = 128
        const val REQUEST_ID_BASE = 8_000_000_000_000L
    }
}
