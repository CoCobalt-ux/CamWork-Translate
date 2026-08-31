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

/** Подтверждает строку двумя наблюдениями и не переотправляет её при кратком мерцании UIA. */
internal class LiveLensChangeDetector {
    private data class Track(
        var block: LiveLensTextBlock,
        var observations: Int,
        var lastSeenAt: Long,
        var emitted: Boolean
    )

    private val tracks = mutableListOf<Track>()
    private var scanCount = 0

    val isCalibrated: Boolean
        get() = scanCount >= REQUIRED_OBSERVATIONS

    fun accept(
        current: List<LiveLensTextBlock>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<LiveLensTextBlock> {
        scanCount++
        val claimed = mutableSetOf<Track>()
        val added = mutableListOf<LiveLensTextBlock>()

        current.sortedBy { it.anchor.y }.forEach { block ->
            val track = tracks.asSequence()
                .filterNot(claimed::contains)
                .filter { candidate -> identity(candidate.block) == identity(block) }
                .filter { candidate ->
                    kotlin.math.abs(candidate.block.anchor.y - block.anchor.y) <= POSITION_TOLERANCE_PX
                }
                .minByOrNull { candidate -> kotlin.math.abs(candidate.block.anchor.y - block.anchor.y) }
                ?: Track(
                    block = block,
                    observations = 0,
                    lastSeenAt = nowMillis,
                    emitted = false
                ).also(tracks::add)

            claimed += track
            track.block = block
            track.observations++
            track.lastSeenAt = nowMillis
            if (!track.emitted && track.observations >= REQUIRED_OBSERVATIONS) {
                track.emitted = true
                added += block
            }
        }

        tracks.removeAll { track ->
            track !in claimed && nowMillis - track.lastSeenAt > TRACK_RETENTION_MS
        }
        return added
    }

    fun reset() {
        tracks.clear()
        scanCount = 0
    }

    private fun identity(block: LiveLensTextBlock): String =
        "${block.speaker.orEmpty().trim().lowercase(Locale.ROOT)}\u0000" +
            block.text.trim().lowercase(Locale.ROOT)

    private companion object {
        const val REQUIRED_OBSERVATIONS = 2
        const val POSITION_TOLERANCE_PX = 180
        const val TRACK_RETENTION_MS = 5_000L
    }
}

/** Связывает наблюдение, MVI-перевод и позиционный неблокирующий оверлей. */
internal class LiveLensController(
    private val scope: CoroutineScope,
    private val strings: LiveLensStrings,
    private val initialBounds: () -> Rectangle,
    private val dispatch: (MainIntent) -> Unit,
    private val saveBounds: (Rectangle) -> Unit,
    private val logger: Logger,
    private val reader: LiveLensTextReader = WindowsAccessibilityLiveLensTextReader()
) {
    private val sequence = AtomicLong(0L)
    private val detector = LiveLensChangeDetector()
    private val requests = mutableMapOf<Long, LiveLensTextBlock>()
    private val pending = ArrayDeque<LiveLensTextBlock>()
    private var pollJob: Job? = null
    @Volatile
    private var activeRequestId: Long? = null
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
            else -> Unit
        }
    }

    private fun start(bounds: Rectangle, resume: Boolean) {
        lensBounds = window.currentScanBounds()
        saveBounds(Rectangle(bounds))
        if (!resume) {
            detector.reset()
            pending.clear()
            lastMessageCount = -1
        }
        pollJob?.cancel()
        logger.info(
            "LIVE-перевод запущен: область интерфейса=${bounds.x},${bounds.y} ${bounds.width}x${bounds.height}; " +
                "область сканирования=${lensBounds.x},${lensBounds.y} ${lensBounds.width}x${lensBounds.height}"
        )
        runOnUi { window.beginWatching(clearCards = !resume, calibrating = !resume) }
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val fragments = runCatching { reader.read(Rectangle(lensBounds)) }
                    .onFailure { error -> logger.error("Ошибка чтения LIVE-области", error) }
                    .getOrDefault(emptyList())
                val messages = assembleLiveLensMessages(fragments, lensBounds)
                val wasCalibrated = detector.isCalibrated
                val added = detector.accept(messages)
                if (messages.size != lastMessageCount || added.isNotEmpty()) {
                    logger.info(
                        "LIVE-сканирование: фрагментов=${fragments.size}, сообщений=${messages.size}, " +
                            "подтверждено=${added.size}, " +
                            "очередь=${pending.size}, активен=${activeRequestId != null}"
                    )
                    lastMessageCount = messages.size
                }
                runOnUi { window.syncSources(messages) }
                if (wasCalibrated) {
                    // Новая реплика важнее старого хвоста, иначе чат успевает уйти далеко вперёд.
                    added.forEach { block -> queue(block, priority = true) }
                } else {
                    // На первом устойчивом снимке начинаем снизу — с самой свежей видимой строки.
                    added.asReversed().forEach { block -> queue(block, priority = false) }
                }
                if (activeRequestId == null) {
                    runOnUi {
                        window.setMode(
                            if (detector.isCalibrated) LiveLensMode.WATCHING else LiveLensMode.READING,
                            when {
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
    private fun queue(
        block: LiveLensTextBlock,
        priority: Boolean
    ) {
        val normalized = block.text.trim()
        if (normalized.isBlank()) return
        val alreadyQueued = pending.any { it.isSameSourceAs(block) }
        val alreadyRunning = requests.values.any { it.isSameSourceAs(block) }
        if (alreadyQueued || alreadyRunning) return

        if (pending.size >= MAX_PENDING_BLOCKS) {
            if (priority) pending.removeLast() else pending.removeFirst()
        }
        if (priority) {
            pending.addFirst(block.copy(text = normalized))
        } else {
            pending.addLast(block.copy(text = normalized))
        }
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
        const val POLL_INTERVAL_MS = 700L
        const val MAX_PENDING_BLOCKS = 30
    }
}

private fun LiveLensTextBlock.isSameSourceAs(other: LiveLensTextBlock): Boolean =
    speaker.equals(other.speaker, ignoreCase = true) &&
    text.equals(other.text, ignoreCase = true) &&
        kotlin.math.abs(anchor.y - other.anchor.y) <= 34
