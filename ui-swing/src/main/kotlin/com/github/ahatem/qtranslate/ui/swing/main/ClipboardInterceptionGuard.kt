package com.github.ahatem.qtranslate.ui.swing.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * Координирует временное использование clipboard для чтения выделенного текста.
 *
 * Системный снимок экрана имеет приоритет над любой уже начатой операцией. Поколение запрещает
 * позднему `finally` вернуть старое содержимое, а список Job прерывает отложенный Ctrl+C.
 * Проверка и восстановление выполняются под тем же монитором, что и invalidation: Print Screen
 * не может вклиниться между последней проверкой и `Clipboard.setContents`.
 */
internal class ClipboardInterceptionGuard {
    internal class Lease internal constructor(internal val generation: Long)

    private val monitor = Any()
    private var generation = 0L
    private val activeJobs = LinkedHashSet<Job>()

    suspend fun <T> track(block: suspend (Lease) -> T): T? {
        val job = currentCoroutineContext()[Job]
        val lease = synchronized(monitor) {
            if (job != null) activeJobs += job
            Lease(generation)
        }

        return try {
            if (!isCurrent(lease)) null else block(lease)
        } finally {
            if (job != null) synchronized(monitor) { activeJobs -= job }
        }
    }

    fun isCurrent(lease: Lease): Boolean =
        synchronized(monitor) { lease.generation == generation }

    /** Отменяет текущие/ожидающие операции и навсегда инвалидирует их право восстановления. */
    fun invalidateForScreenCapture() {
        val jobs = synchronized(monitor) {
            generation++
            activeJobs.toList()
        }
        jobs.forEach { job ->
            job.cancel(CancellationException("System screen capture owns the clipboard"))
        }
    }

    /**
     * Атомарно восстанавливает старое содержимое только если операция всё ещё актуальна и
     * clipboard по-прежнему содержит данные, полученные нашим синтетическим Ctrl+C.
     */
    fun restoreIfAllowed(
        lease: Lease,
        ownsCurrentClipboard: () -> Boolean,
        restore: () -> Unit
    ): Boolean = synchronized(monitor) {
        if (lease.generation != generation || !ownsCurrentClipboard()) return@synchronized false
        restore()
        true
    }
}
