package com.github.ahatem.qtranslate.core.main.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.EnumMap

/**
 * Владелец фоновых переводов. Последний запрос вытесняет только запрос своего [TranslationLane],
 * поэтому ввод в главном окне, явный Shift и пассивный AUTO больше не отменяют друг друга.
 */
internal class TranslationJobCoordinator(
    private val scope: CoroutineScope
) {
    private val lock = Any()
    private val jobs = EnumMap<TranslationLane, Job>(TranslationLane::class.java)

    fun launchLatest(
        lane: TranslationLane,
        cancellationReason: String,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        lateinit var newJob: Job
        synchronized(lock) {
            jobs[lane]?.cancel(CancellationException(cancellationReason))
            newJob = scope.launch(start = CoroutineStart.LAZY, block = block)
            jobs[lane] = newJob
            newJob.invokeOnCompletion {
                synchronized(lock) {
                    if (jobs[lane] === newJob) jobs.remove(lane)
                }
            }
            newJob.start()
        }
        return newJob
    }

    fun cancel(lane: TranslationLane, reason: String) {
        synchronized(lock) {
            jobs.remove(lane)?.cancel(CancellationException(reason))
        }
    }
}
