package com.github.ahatem.qtranslate.ui.swing.livelens

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationFailureKind
import com.github.ahatem.qtranslate.core.main.mvi.MainEvent
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Rectangle
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/**
 * Сравнивает законченные снимки чата и возвращает только хвост, которого не было в прошлом
 * снимке. Координаты намеренно не участвуют в идентичности: прокрутка двигает весь чат, но не
 * превращает старое сообщение в новое.
 */
internal class LiveLensChangeDetector {
    private var previousSnapshot = emptyList<LiveLensTextBlock>()
    private var scanCount = 0

    val isCalibrated: Boolean
        get() = scanCount > 0

    fun accept(current: List<LiveLensTextBlock>): List<LiveLensTextBlock> {
        scanCount++
        val snapshot = current.sortedBy { block -> block.anchor.y }
        if (snapshot.isEmpty()) {
            // Пустой OCR-кадр бывает во время анимации или перерисовки сайта. Он не должен
            // стирать базовую линию и заставлять следующий нормальный кадр выглядеть новым.
            return emptyList()
        }

        if (previousSnapshot.isEmpty()) {
            previousSnapshot = snapshot
            return snapshot
        }

        var previousCursor = 0
        var lastMatchedCurrentIndex = -1
        snapshot.forEachIndexed { currentIndex, currentBlock ->
            val matchedIndex = (previousCursor until previousSnapshot.size).firstOrNull { previousIndex ->
                isSameMessage(previousSnapshot[previousIndex], currentBlock)
            }
            if (matchedIndex != null) {
                previousCursor = matchedIndex + 1
                lastMatchedCurrentIndex = currentIndex
            }
        }

        val added = if (lastMatchedCurrentIndex >= 0) {
            snapshot.drop(lastMatchedCurrentIndex + 1)
        } else {
            // При полном скачке страницы нельзя снова отправлять переводчику весь экран. Самая
            // нижняя строка — единственная безопасная гипотеза о действительно новом сообщении.
            snapshot.takeLast(1)
        }
        previousSnapshot = snapshot
        return added
    }

    fun reset() {
        previousSnapshot = emptyList()
        scanCount = 0
    }

    private fun isSameMessage(first: LiveLensTextBlock, second: LiveLensTextBlock): Boolean =
        normalize(first.speaker.orEmpty()) == normalize(second.speaker.orEmpty()) &&
            normalize(first.text) == normalize(second.text)

    private fun normalize(text: String): String = buildString(text.length) {
        var pendingSpace = false
        text.lowercase(Locale.ROOT).forEach { character ->
            when {
                character.isLetterOrDigit() -> {
                    if (pendingSpace && isNotEmpty()) append(' ')
                    append(character)
                    pendingSpace = false
                }
                isNotEmpty() -> pendingSpace = true
            }
        }
    }.trim()
}

/** Связывает снимки области, MVI-перевод и стабильную неблокирующую ленту. */
internal class LiveLensController(
    private val scope: CoroutineScope,
    private val strings: LiveLensStrings,
    private val initialBounds: () -> Rectangle,
    private val dispatch: (MainIntent) -> Unit,
    private val saveBounds: (Rectangle) -> Unit,
    private val logger: Logger,
    private val reader: LiveLensTextReader = defaultLiveLensTextReader(),
    private val frameCapture: LiveLensFrameCapture? = defaultLiveLensFrameCapture()
) {
    private val sequence = AtomicLong(0L)
    private val detector = LiveLensChangeDetector()
    private val requests = mutableMapOf<Long, LiveLensTextBlock>()
    private val frameSnapshots = mutableMapOf<Long, LiveLensFrameSnapshot>()
    private val pending = ArrayDeque<LiveLensTextBlock>()
    private var pollJob: Job? = null
    @Volatile
    private var activeRequestId: Long? = null
    @Volatile
    private var activeFrameRecognitionId: Long? = null
    @Volatile
    private var pixelCaptureEnabled = false
    @Volatile
    private var lensBounds = Rectangle()
    private var lastMessageCount = -1

    private val window by lazy {
        LiveLensWindow(
            strings = strings,
            onStart = ::start,
            onPause = ::pause,
            onClose = ::close,
            onBoundsChanged = { bounds ->
                lensBounds = Rectangle(bounds)
                saveBounds(Rectangle(bounds))
            }
        )
    }

    fun open() {
        pause()
        runOnUi {
            lensBounds = initialBounds()
            window.showSetup(Rectangle(lensBounds))
        }
    }

    fun handle(event: MainEvent) {
        when (event) {
            is MainEvent.LiveLensTranslationCompleted -> complete(
                requestId = event.requestId,
                translatedText = event.translatedText,
                provider = event.translatorName,
                failure = null
            )
            is MainEvent.LiveLensTranslationFinished -> complete(
                requestId = event.requestId,
                translatedText = null,
                provider = "",
                failure = event.failure
            )
            is MainEvent.LiveLensFrameRecognized -> completeFrameRecognition(
                requestId = event.requestId,
                recognizedText = event.text
            )
            is MainEvent.LiveLensFrameRecognitionFinished -> completeFrameRecognition(
                requestId = event.requestId,
                recognizedText = null
            )
            else -> Unit
        }
    }

    private fun start(bounds: Rectangle, resume: Boolean) {
        lensBounds = window.currentScanBounds()
        pixelCaptureEnabled = frameCapture != null && window.enablePixelCapture()
        saveBounds(Rectangle(bounds))
        if (!resume) {
            detector.reset()
            pending.clear()
            lastMessageCount = -1
        }
        pollJob?.cancel()
        logger.info(
            "LIVE-перевод запущен: область интерфейса=${bounds.x},${bounds.y} ${bounds.width}x${bounds.height}; " +
                "область сканирования=${lensBounds.x},${lensBounds.y} ${lensBounds.width}x${lensBounds.height}; " +
                "пиксельный OCR=${if (pixelCaptureEnabled) "доступен" else "недоступен"}"
        )
        runOnUi { window.beginWatching(clearCards = !resume, calibrating = !resume) }
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val fragments = runCatching { reader.read(Rectangle(lensBounds)) }
                    .onFailure { error -> logger.error("Ошибка чтения LIVE-области", error) }
                    .getOrDefault(emptyList())
                val messages = assembleLiveLensMessages(fragments, lensBounds)
                when (requestFrameRecognition(messages, fragments.size)) {
                    LiveLensFrameRequest.STARTED,
                    LiveLensFrameRequest.BUSY -> Unit
                    LiveLensFrameRequest.UNAVAILABLE -> processSnapshot(
                        messages = messages,
                        fragmentCount = fragments.size,
                        source = LiveLensInputSource.ACCESSIBILITY
                    )
                }
                runOnUi { window.snapshotCompleted(POLL_INTERVAL_MS) }
                if (activeRequestId == null) {
                    runOnUi {
                        window.setMode(
                            if (activeFrameRecognitionId != null || !detector.isCalibrated) {
                                LiveLensMode.READING
                            } else {
                                LiveLensMode.WATCHING
                            },
                            when {
                                activeFrameRecognitionId != null -> strings.reading
                                !detector.isCalibrated -> strings.reading
                                messages.isEmpty() -> strings.noText
                                else -> strings.watching
                            }
                        )
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        if (resume) submitNext()
    }

    @Synchronized
    private fun requestFrameRecognition(
        accessibilityMessages: List<LiveLensTextBlock>,
        fragmentCount: Int
    ): LiveLensFrameRequest {
        if (!pixelCaptureEnabled) return LiveLensFrameRequest.UNAVAILABLE
        if (activeFrameRecognitionId != null) return LiveLensFrameRequest.BUSY
        val image = frameCapture?.capture(Rectangle(lensBounds))
            ?: return LiveLensFrameRequest.UNAVAILABLE
        val requestId = sequence.incrementAndGet()
        activeFrameRecognitionId = requestId
        frameSnapshots[requestId] = LiveLensFrameSnapshot(
            accessibilityMessages = accessibilityMessages,
            fragmentCount = fragmentCount
        )
        logger.debug(
            "LIVE OCR-кадр: id=$requestId, размер=${image.width}x${image.height}, байт=${image.bytes.size}"
        )
        dispatch(MainIntent.RecognizeLiveLensFrame(image, requestId))
        return LiveLensFrameRequest.STARTED
    }

    @Synchronized
    private fun detectNewMessages(messages: List<LiveLensTextBlock>): List<LiveLensTextBlock> =
        detector.accept(messages)

    @Synchronized
    private fun completeFrameRecognition(requestId: Long, recognizedText: String?) {
        if (requestId != activeFrameRecognitionId) return
        activeFrameRecognitionId = null
        val frameSnapshot = frameSnapshots.remove(requestId) ?: return
        val ocrMessages = recognizedText
            ?.takeIf(String::isNotBlank)
            ?.let { text -> liveLensOcrTextBlocks(text, lensBounds) }
            .orEmpty()
        val selection = selectLiveLensInput(
            accessibilityMessages = frameSnapshot.accessibilityMessages,
            ocrMessages = ocrMessages
        )
        processSnapshot(
            messages = selection.messages,
            fragmentCount = frameSnapshot.fragmentCount,
            source = selection.source
        )
        logger.debug(
            "LIVE-сверка источников: accessibility=${frameSnapshot.accessibilityMessages.size}, " +
                "ocr=${ocrMessages.size}, выбран=${selection.source.logName}, " +
                "символов OCR=${recognizedText?.length ?: 0}"
        )
    }

    @Synchronized
    private fun processSnapshot(
        messages: List<LiveLensTextBlock>,
        fragmentCount: Int,
        source: LiveLensInputSource
    ) {
        val added = detectNewMessages(messages)
        if (messages.size != lastMessageCount || added.isNotEmpty()) {
            logger.info(
                "LIVE-сканирование: источник=${source.logName}, фрагментов=$fragmentCount, " +
                    "сообщений=${messages.size}, подтверждено=${added.size}, " +
                    "очередь=${pending.size}, активен=${activeRequestId != null}"
            )
            lastMessageCount = messages.size
        }
        added.forEach(::queue)
    }

    @Synchronized
    private fun queue(block: LiveLensTextBlock) {
        val normalized = block.text.trim()
        if (normalized.isBlank()) return

        if (pending.size >= MAX_PENDING_BLOCKS) {
            pending.removeFirst()
        }
        pending.addLast(block.copy(text = normalized))
        submitNext()
    }

    @Synchronized
    private fun submitNext() {
        if (activeRequestId != null || pending.isEmpty()) return
        val block = pending.removeFirst()
        val requestId = sequence.incrementAndGet()
        activeRequestId = requestId
        requests[requestId] = block
        logger.debug("LIVE-запрос: id=$requestId, длина=${block.text.length}, y=${block.anchor.y}")
        runOnUi { window.setMode(LiveLensMode.TRANSLATING, strings.translating) }
        dispatch(MainIntent.TranslateLiveLensText(block.text, requestId))
    }

    @Synchronized
    private fun complete(
        requestId: Long,
        translatedText: String?,
        provider: String,
        failure: TranslationFailureKind?
    ) {
        val block = requests.remove(requestId)
        if (requestId != activeRequestId || block == null) return
        activeRequestId = null

        runOnUi {
            when {
                !translatedText.isNullOrBlank() -> {
                    window.showTranslation(requestId, block, translatedText, provider)
                    window.setMode(LiveLensMode.WATCHING, strings.watching)
                }
                failure != null && failure != TranslationFailureKind.CANCELLED ->
                    window.setMode(LiveLensMode.ERROR, strings.failed)
                else -> window.setMode(LiveLensMode.WATCHING, strings.watching)
            }
        }
        submitNext()
    }

    @Synchronized
    private fun pause() {
        pollJob?.cancel()
        pollJob = null
        activeRequestId?.let { requestId ->
            requests.remove(requestId)?.let(pending::addFirst)
        }
        activeRequestId = null
        activeFrameRecognitionId = null
        frameSnapshots.clear()
        dispatch(MainIntent.CancelLiveLensTranslation)
    }

    fun close() {
        pause()
        runOnUi { window.dispose() }
    }

    private fun runOnUi(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val MAX_PENDING_BLOCKS = 30
    }
}

private data class LiveLensFrameSnapshot(
    val accessibilityMessages: List<LiveLensTextBlock>,
    val fragmentCount: Int
)

private enum class LiveLensFrameRequest { STARTED, BUSY, UNAVAILABLE }
