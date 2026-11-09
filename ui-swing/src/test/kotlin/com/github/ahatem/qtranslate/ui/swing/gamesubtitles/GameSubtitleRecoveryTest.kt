package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationFailureKind
import com.github.ahatem.qtranslate.core.main.mvi.MainEvent
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.ui.swing.livelens.LiveLensFrameCapture
import java.awt.Rectangle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GameSubtitleRecoveryTest {
    @Test
    fun `ошибка OCR повторяет неизменный кадр и затем показывает перевод`() = runTest {
        val fixture = fixture()
        runCurrent()
        val first = fixture.recognitions().single()
        fixture.controller.handle(MainEvent.LiveLensFrameRecognitionFinished(first.requestId))
        advanceTimeBy(650)
        runCurrent()
        val retry = fixture.recognitions().last()
        assertTrue(retry.requestId != first.requestId)
        fixture.controller.handle(MainEvent.LiveLensFrameRecognized(retry.requestId, "A recovered subtitle"))
        fixture.finishTranslation("Восстановленная реплика")
        assertEquals(listOf("Восстановленная реплика"), fixture.view.translations)
    }

    @Test
    fun `пауза отменяет старый запрос и продолжение повторно читает тот же кадр`() = runTest {
        val fixture = fixture()
        runCurrent()
        val first = fixture.recognitions().single()
        fixture.controller.handle(MainEvent.LiveLensFrameRecognized(first.requestId, "A paused subtitle"))
        val oldTranslation = fixture.translations().single()
        fixture.view.actions.togglePause()
        fixture.view.actions.togglePause()
        runCurrent()
        assertEquals(2, fixture.recognitions().size)
        fixture.controller.handle(MainEvent.LiveLensTranslationCompleted(
            oldTranslation.requestId, oldTranslation.text, "Устаревший ответ", "Тест"
        ))
        assertTrue(fixture.view.translations.isEmpty())
        val retry = fixture.recognitions().last()
        fixture.controller.handle(MainEvent.LiveLensFrameRecognized(retry.requestId, "A paused subtitle"))
        fixture.finishTranslation("Новый ответ")
        assertEquals(listOf("Новый ответ"), fixture.view.translations)
    }

    @Test
    fun `неизменный пустой кадр сбрасывает старую реплику без повторных OCR запросов`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.recognize("A subtitle that returns")
        fixture.finishTranslation("Повторяемая реплика")
        fixture.frame = frame(2)
        advanceTimeBy(650)
        runCurrent()
        fixture.recognize("")
        val requestsBeforeEmptyWait = fixture.recognitions().size
        advanceTimeBy(4_550)
        runCurrent()
        assertEquals(requestsBeforeEmptyWait, fixture.recognitions().size)
        fixture.frame = frame(1)
        advanceTimeBy(650)
        runCurrent()
        fixture.recognize("A subtitle that returns")
        assertEquals(listOf("Повторяемая реплика", "Повторяемая реплика"), fixture.view.translations)
    }

    @Test
    fun `потерянное завершение OCR освобождается защитным таймаутом`() = runTest {
        val fixture = fixture(timeoutMillis = 1_000)
        runCurrent()
        val old = fixture.recognitions().single()
        advanceTimeBy(1_950)
        runCurrent()
        assertEquals(2, fixture.recognitions().size)
        fixture.controller.handle(MainEvent.LiveLensFrameRecognized(old.requestId, "A stale frame"))
        assertTrue(fixture.translations().isEmpty())
        fixture.recognize("A current frame")
        fixture.finishTranslation("Работа продолжена")
        assertEquals(listOf("Работа продолжена"), fixture.view.translations)
    }

    @Test
    fun `потерянное завершение перевода не оставляет вечную блокировку очереди`() = runTest {
        val fixture = fixture(timeoutMillis = 1_000)
        runCurrent()
        fixture.recognize("A timed out translation")
        advanceTimeBy(1_950)
        runCurrent()
        assertEquals(2, fixture.translations().size)
        fixture.finishTranslation("Ответ после таймаута")
        assertEquals(listOf("Ответ после таймаута"), fixture.view.translations)
    }

    @Test
    fun `ошибка перевода повторяется с задержкой и не требует перезапуска`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.recognize("A failed translation")
        val failed = fixture.translations().single()
        fixture.controller.handle(MainEvent.LiveLensTranslationFinished(failed.requestId, TranslationFailureKind.NETWORK))
        assertEquals(1, fixture.translations().size)
        advanceTimeBy(650)
        runCurrent()
        assertEquals(2, fixture.translations().size)
        fixture.finishTranslation("Успешная повторная попытка")
        assertEquals(listOf("Успешная повторная попытка"), fixture.view.translations)
    }

    @Test
    fun `завершение без ошибки не переводит повторно текст на языке пользователя`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.recognize("A deliberately skipped translation")
        fixture.controller.handle(MainEvent.LiveLensTranslationFinished(fixture.translations().single().requestId))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, fixture.translations().size)
    }

    @Test
    fun `новая реплика не ждёт отложенного повтора старой ошибки`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.recognize("An old failed translation")
        fixture.controller.handle(MainEvent.LiveLensTranslationFinished(
            fixture.translations().single().requestId, TranslationFailureKind.NETWORK
        ))
        fixture.frame = frame(2)
        advanceTimeBy(650)
        runCurrent()
        fixture.recognize("A different current subtitle")
        // Старый повтор мог уже стартовать на границе такта; он не мешает новой реплике после завершения.
        fixture.finishTranslation("Завершённый предыдущий запрос")
        assertEquals("A different current subtitle", fixture.translations().last().text)
        fixture.finishTranslation("Актуальный перевод")
        assertEquals("Актуальный перевод", fixture.view.translations.last())
    }

    @Test
    fun `исключение захвата не останавливает цикл навсегда`() = runTest {
        val fixture = fixture()
        fixture.captureError = IllegalStateException("Временная ошибка захвата")
        runCurrent()
        assertTrue(fixture.recognitions().isEmpty())
        fixture.captureError = null
        advanceTimeBy(650)
        runCurrent()
        assertEquals(1, fixture.recognitions().size)
    }

    @Test
    fun `закрытие отменяет фоновые попытки и игнорирует поздние ответы`() = runTest {
        val fixture = fixture()
        runCurrent()
        val old = fixture.recognitions().single()
        fixture.controller.close()
        advanceTimeBy(60_000)
        runCurrent()
        fixture.controller.handle(MainEvent.LiveLensFrameRecognized(old.requestId, "An obsolete frame"))
        assertEquals(1, fixture.recognitions().size)
        assertTrue(fixture.translations().isEmpty())
        assertTrue(fixture.view.disposed)
    }

    @Test
    fun `сто циклов паузы и поздних ответов не останавливают LIVE`() = runTest {
        val fixture = fixture()
        runCurrent()
        repeat(100) { index ->
            val stale = fixture.recognitions().last()
            fixture.view.actions.togglePause()
            fixture.view.actions.togglePause()
            runCurrent()
            fixture.controller.handle(MainEvent.LiveLensFrameRecognized(stale.requestId, "A stale frame $index"))
            fixture.recognize("A current subtitle number $index")
            fixture.finishTranslation("Перевод $index")
        }
        assertEquals(100, fixture.view.translations.size)
        assertEquals("Перевод 99", fixture.view.translations.last())
    }

    @Test
    fun `пауза не ждёт монитор контроллера пока захват заблокирован`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val enteredCapture = CountDownLatch(1)
        val releaseCapture = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val view = FakeView().apply {
            beforeCapture = {
                enteredCapture.countDown()
                check(releaseCapture.await(10, TimeUnit.SECONDS)) { "Захват не освобождён тестом" }
            }
        }
        val intents = CopyOnWriteArrayList<MainIntent>()
        val controller = GameSubtitleController(
            scope, strings, { intents.add(it) }, logger, {}, LiveLensFrameCapture { frame(1) },
            windowFactory = { actions -> view.apply { this.actions = actions } },
            uiExecutor = { it() }
        )
        try {
            controller.open(Rectangle(0, 0, 100, 100))
            assertTrue(enteredCapture.await(5, TimeUnit.SECONDS))
            val pauseThread = thread(isDaemon = true) {
                view.actions.togglePause()
                paused.countDown()
            }
            assertTrue(paused.await(3, TimeUnit.SECONDS), "Пауза заблокирована монитором фонового захвата")
            releaseCapture.countDown()
            pauseThread.join(1_000)
            assertFalse(intents.any { it is MainIntent.RecognizeLiveLensFrame })
        } finally {
            releaseCapture.countDown()
            controller.close()
            scope.cancel()
        }
    }

    private fun TestScope.fixture(timeoutMillis: Long = 35_000L): Fixture {
        val fixture = Fixture()
        fixture.controller = GameSubtitleController(
            scope = backgroundScope,
            strings = strings,
            dispatch = { fixture.intents.add(it) },
            logger = logger,
            onChangeArea = {},
            frameCapture = LiveLensFrameCapture {
                fixture.captureError?.let { throw it }
                fixture.frame
            },
            windowFactory = { actions -> fixture.view.apply { this.actions = actions } },
            uiExecutor = { it() },
            pollingDispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
            requestTimeoutMillis = timeoutMillis
        )
        fixture.controller.open(Rectangle(0, 0, 100, 100))
        return fixture
    }

    private class Fixture {
        val intents = CopyOnWriteArrayList<MainIntent>()
        val view = FakeView()
        lateinit var controller: GameSubtitleController
        var frame = frame(1)
        var captureError: Exception? = null
        fun recognitions() = intents.filterIsInstance<MainIntent.RecognizeLiveLensFrame>()
        fun translations() = intents.filterIsInstance<MainIntent.TranslateLiveLensText>()
        fun recognize(text: String) {
            controller.handle(MainEvent.LiveLensFrameRecognized(recognitions().last().requestId, text))
        }
        fun finishTranslation(text: String) {
            val request = translations().last()
            controller.handle(MainEvent.LiveLensTranslationCompleted(request.requestId, request.text, text, "Тест"))
        }
    }

    private class FakeView : GameSubtitleView {
        lateinit var actions: GameSubtitleActions
        val translations = CopyOnWriteArrayList<String>()
        var beforeCapture: () -> Unit = {}
        var disposed = false
        override fun showAt(captureBounds: Rectangle) { disposed = false }
        override fun setMode(mode: GameSubtitleWindowMode) = Unit
        override fun showTranslation(text: String, replaceLatest: Boolean) { translations += text }
        override fun resetTranslations() { translations.clear() }
        override fun <T> captureWithoutOverlay(action: () -> T): T {
            beforeCapture()
            return action()
        }
        override fun dispose() { disposed = true }
    }

    private companion object {
        fun frame(value: Int) = ImageData(byteArrayOf(value.toByte()), "png", 100, 100)
        val strings = GameSubtitleStrings("Тест", "Активно", "Сканирование", "Перевод", "Пауза", "Ошибка", "Пауза", "Продолжить", "Область", "Закрыть")
        val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
    }
}
