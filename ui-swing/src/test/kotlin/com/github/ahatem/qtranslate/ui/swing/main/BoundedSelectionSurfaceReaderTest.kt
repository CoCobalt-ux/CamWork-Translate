package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import java.awt.Point
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundedSelectionSurfaceReaderTest {
    @Test
    fun `зависшая UIA даёт fallback без очереди и восстанавливается после ответа`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val reader = reader { _, _ ->
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Тест не освободил UIA" }
            }
            READ_ONLY
        }
        try {
            val first = async(Dispatchers.Default) { reader.inspect(Point(10, 20), null) }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Нативный запрос должен действительно начаться")
            assertEquals(UNKNOWN, withTimeout(5_000) { first.await() })

            val following = List(32) {
                async(Dispatchers.Default) { reader.inspect(Point(10, 20), null) }
            }
            assertTrue(withTimeout(5_000) { following.awaitAll() }.all { it == UNKNOWN })
            assertEquals(1, calls.get(), "Зависшая UIA не должна накапливать фоновые задания")

            release.countDown()
            awaitRecovered(reader)
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            reader.close()
        }
    }

    @Test
    fun `ошибка нативного запроса не прерывает следующий запрос поверхности`() = runBlocking {
        val logger = TestLogger()
        val calls = AtomicInteger()
        val reader = reader(logger) { _, _ ->
            if (calls.incrementAndGet() == 1) throw IllegalStateException("Управляемый отказ UIA")
            READ_ONLY
        }
        try {
            assertEquals(UNKNOWN, reader.inspect(Point(), null))
            awaitRecovered(reader)
            assertEquals(2, calls.get())
            assertEquals(1, logger.failures.get())
        } finally {
            reader.close()
        }
    }

    @Test
    fun `CancellationException сторонней UIA не отменяет активного клиента`() = runBlocking {
        val logger = TestLogger()
        val calls = AtomicInteger()
        val reader = reader(logger) { _, _ ->
            if (calls.incrementAndGet() == 1) throw CancellationException("Отказ внешнего accessibility API")
            READ_ONLY
        }
        try {
            assertEquals(UNKNOWN, reader.inspect(Point(), null))
            awaitRecovered(reader)
            assertEquals(2, calls.get())
            assertEquals(1, logger.failures.get())
        } finally {
            reader.close()
        }
    }

    @Test
    fun `отмена пользователя прекращает ожидание но не создаёт второй зависший native вызов`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val reader = reader(timeoutMillis = 5_000) { _, _ ->
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Тест не освободил UIA" }
            }
            READ_ONLY
        }
        try {
            val pending = async(Dispatchers.Default) { reader.inspect(Point(), null) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(UNKNOWN, reader.inspect(Point(), null))
            assertEquals(1, calls.get())
            release.countDown()
            awaitRecovered(reader)
        } finally {
            release.countDown()
            reader.close()
        }
    }

    private fun reader(
        logger: TestLogger = TestLogger(),
        timeoutMillis: Long = 150,
        inspect: (Point, ClipboardSelectionEvidence?) -> SelectionSurfaceDetection
    ) = BoundedSelectionSurfaceReader(
        logger = logger,
        timeoutMillis = timeoutMillis,
        inspectBlocking = inspect,
        inspectFallback = { _, _ -> UNKNOWN }
    )

    private suspend fun awaitRecovered(reader: BoundedSelectionSurfaceReader) {
        withTimeout(5_000) {
            while (reader.inspect(Point(), null) != READ_ONLY) delay(5)
        }
    }

    private class TestLogger : Logger {
        val failures = AtomicInteger()
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) { failures.incrementAndGet() }
    }

    private companion object {
        val UNKNOWN = SelectionSurfaceDetection(SelectionSurface.UNKNOWN, null, null)
        val READ_ONLY = SelectionSurfaceDetection(SelectionSurface.READ_ONLY, null, null)
    }
}
