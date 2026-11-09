package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.github.ahatem.qtranslate.ui.swing.testing.UiTestLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResilientFlowCollectorTest {
    @Test
    fun `тысяча событий с повторными сбоями не останавливает обработку переводов`() = runBlocking {
        val logger = UiTestLogger()
        val processed = mutableListOf<Int>()
        withTimeout(5_000) {
            (0 until 1_000).asFlow().collectWithRecovery(logger, "тестовый переводчик") { event ->
                if (event % 10 == 0) throw IOException("Закрытые данные перевода")
                processed += event
            }
        }
        assertEquals(900, processed.size)
        assertEquals(999, processed.last())
        assertEquals(100, logger.errors.size)
        assertTrue(logger.errors.none { "Закрытые данные" in it })
    }

    @Test
    fun `наблюдатель повторно подключается после ошибки источника`() = runBlocking {
        var subscriptions = 0
        val processed = mutableListOf<String>()
        val events = flow {
            subscriptions++
            if (subscriptions == 1) throw IOException("Временный сбой")
            emit("перевод восстановлен")
        }
        withTimeout(5_000) {
            events.collectWithRecovery(UiTestLogger(), "источник", retryDelayMillis = 1) { processed += it }
        }
        assertEquals(2, subscriptions)
        assertEquals(listOf("перевод восстановлен"), processed)
    }

    @Test
    fun `отмена сохраняет структурированное завершение обработчика`() = runBlocking {
        val logger = UiTestLogger()
        val job = launch {
            flowOf(1, 2).collectWithRecovery(logger, "отмена") {
                currentCoroutineContext().cancel(CancellationException("Закрытие окна"))
                currentCoroutineContext().ensureActive()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(logger.errors.isEmpty())
    }

    @Test
    fun `таймаут одной операции не отключает последующие события`() = runBlocking {
        val logger = UiTestLogger()
        val processed = mutableListOf<Int>()
        flowOf(1, 2).collectWithRecovery(logger, "таймаут операции") {
            if (it == 1) withTimeout(1) { awaitCancellation() }
            processed += it
        }
        assertEquals(listOf(2), processed)
        assertEquals(1, logger.errors.size)
    }

    @Test
    fun `таймаут источника не убивает подписку при живом контексте`() = runBlocking {
        var attempts = 0
        val processed = mutableListOf<Int>()
        flow {
            if (++attempts == 1) withTimeout(1) { awaitCancellation() }
            emit(42)
        }.collectWithRecovery(UiTestLogger(), "таймаут источника", retryDelayMillis = 1) { processed += it }
        assertEquals(listOf(42), processed)
    }

    @Test
    fun `фатальная ошибка не маскируется бесконечными повторами`() = runBlocking<Unit> {
        assertFailsWith<AssertionError> {
            flowOf(1).collectWithRecovery(UiTestLogger(), "фатальная ошибка") { throw AssertionError("Ошибка JVM") }
        }
    }
}
