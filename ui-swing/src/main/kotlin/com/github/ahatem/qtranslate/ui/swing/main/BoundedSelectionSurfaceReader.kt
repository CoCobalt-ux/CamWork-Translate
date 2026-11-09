package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.shared.logging.recordFailure
import java.awt.Point
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal fun interface SelectionSurfaceReader {
    suspend fun inspect(point: Point, clipboard: ClipboardSelectionEvidence?): SelectionSurfaceDetection
}

/** Зависший UI Automation чужого окна не занимает все потоки перевода и не скрывает кнопку. */
internal class BoundedSelectionSurfaceReader(
    private val logger: Logger,
    detector: WindowsSelectionSurfaceDetector = WindowsSelectionSurfaceDetector(),
    private val timeoutMillis: Long = 250L,
    private val inspectBlocking: (Point, ClipboardSelectionEvidence?) -> SelectionSurfaceDetection = detector::inspect,
    private val inspectFallback: (Point, ClipboardSelectionEvidence?) -> SelectionSurfaceDetection = detector::inspectFallback,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { action ->
        Thread(action, "camwork-selection-accessibility").apply { isDaemon = true }
    }
) : SelectionSurfaceReader, AutoCloseable {
    private val busy = AtomicBoolean()
    private val timeoutReported = AtomicBoolean()

    init {
        require(timeoutMillis > 0)
    }

    override suspend fun inspect(point: Point, clipboard: ClipboardSelectionEvidence?): SelectionSurfaceDetection {
        if (!busy.compareAndSet(false, true)) return inspectFallback(point, clipboard)
        val result = CompletableDeferred<SelectionSurfaceDetection>()
        try {
            executor.execute {
                try {
                    result.complete(inspectBlocking(Point(point), clipboard))
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                } catch (error: LinkageError) {
                    result.completeExceptionally(error)
                } finally {
                    busy.set(false)
                }
            }
        } catch (error: Exception) {
            busy.set(false)
            logger.recordFailure("запуск определения поверхности выделения", error)
            return inspectFallback(point, clipboard)
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { result.await() } ?: run {
                if (timeoutReported.compareAndSet(false, true)) {
                    logger.warn("UI Automation не ответила вовремя; используется безопасная кнопка выделения")
                }
                inspectFallback(point, clipboard)
            }
        } catch (error: Exception) {
            if (error is CancellationException) currentCoroutineContext().ensureActive()
            logger.recordFailure("определение поверхности выделения", error)
            inspectFallback(point, clipboard)
        } catch (error: LinkageError) {
            logger.recordFailure("нативное определение поверхности выделения", error)
            inspectFallback(point, clipboard)
        }.also { if (!busy.get()) timeoutReported.set(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
