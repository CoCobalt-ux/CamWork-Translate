package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckResponse
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerformSpellCheckUseCaseTest {
    @Test
    fun `ограничение соблюдает Retry After и не засоряет уведомления до успеха`() = runTest {
        val checker = Checker { Err(ServiceError.RateLimitError("429", retryAfterSeconds = 120)) }
        val fixture = Fixture(checker)

        fixture.check("Первый текст")
        repeat(100) { fixture.check("Правка $it") }
        fixture.now = 119_999
        fixture.check("До конца паузы")
        assertEquals(1, checker.calls)
        assertEquals(listOf(Notice(StatusCode.SpellCheckRateLimited(120), NotificationType.INFO)), fixture.notices)

        fixture.now = 120_000
        fixture.check("Провайдер ещё ограничивает запросы")
        assertEquals(2, checker.calls)
        assertEquals(1, fixture.notices.size)
        fixture.now = 239_999
        fixture.check("Пауза продлилась")
        assertEquals(2, checker.calls)

        val correction = Correction("eror", 0, 4, listOf("error"))
        checker.reply = { Ok(SpellCheckResponse("error", listOf(correction))) }
        fixture.now = 240_000
        assertEquals(listOf(correction), fixture.check("eror"))
        assertEquals(3, checker.calls)

        checker.reply = { Err(ServiceError.RateLimitError("429", retryAfterSeconds = 5)) }
        fixture.check("Новое ограничение после успешной проверки")
        assertEquals(Notice(StatusCode.SpellCheckRateLimited(5), NotificationType.INFO), fixture.notices.last())
        assertEquals(2, fixture.notices.size)
    }

    @Test
    fun `пауза без заголовка действует минуту только для ограниченного сервиса`() = runTest {
        val checker = Checker { Err(ServiceError.RateLimitError("429")) }
        val fixture = Fixture(checker)
        fixture.check("Первый текст")
        fixture.now = 59_001
        fixture.check("Явный запрос", requestedByUser = true)
        assertEquals(1, checker.calls)
        assertEquals(Notice(StatusCode.SpellCheckRateLimited(1), NotificationType.INFO), fixture.notices.last())

        val other = Checker { Ok(SpellCheckResponse(it.text, emptyList())) }
        fixture.services.value = mapOf("другой" to other)
        fixture.check("Другой сервис работает сразу")
        assertEquals(1, other.calls)

        fixture.services.value = mapOf("основной" to checker)
        fixture.check("Возврат к ограниченному сервису")
        assertEquals(1, checker.calls)
        fixture.now = 60_000
        checker.reply = { Ok(SpellCheckResponse(it.text, emptyList())) }
        fixture.check("Сервис восстановился")
        assertEquals(2, checker.calls)
    }

    @Test
    fun `параллельные проверки не отправляются пока первый запрос выясняет лимит`() = runTest {
        val response = CompletableDeferred<Result<SpellCheckResponse, ServiceError>>()
        val checker = Checker { response.await() }
        val fixture = Fixture(checker)
        val checks = List(32) { index ->
            async(start = CoroutineStart.UNDISPATCHED) { fixture.check("Текст $index") }
        }
        assertEquals(1, checker.calls)
        response.complete(Err(ServiceError.RateLimitError("429", retryAfterSeconds = 30)))

        assertTrue(checks.awaitAll().all { it.isEmpty() })
        assertEquals(1, checker.calls)
        assertEquals(listOf(Notice(StatusCode.SpellCheckRateLimited(30), NotificationType.INFO)), fixture.notices)
    }

    private class Fixture(checker: Checker) {
        var now = 0L
        val services = MutableStateFlow<Map<String, Service>>(mapOf("основной" to checker))
        val notices = mutableListOf<Notice>()
        private val useCase = PerformSpellCheckUseCase(
            ActiveServiceManager(services, MutableStateFlow(Configuration.DEFAULT)),
            SilentLoggerFactory,
            clockMillis = { now }
        )

        suspend fun check(text: String, requestedByUser: Boolean = false): List<Correction> =
            useCase(MainState(inputText = text), text, requestedByUser) { code, type, _ ->
                notices.add(Notice(code, type))
            }
    }

    private data class Notice(val code: StatusCode, val type: NotificationType)

    private class Checker(
        var reply: suspend (SpellCheckRequest) -> Result<SpellCheckResponse, ServiceError>
    ) : SpellChecker {
        override val key = "test-checker"
        override val name = "Тестовый сервис орфографии"
        override val version = "1"
        override val supportedLanguages = SupportedLanguages.All
        var calls = 0
            private set

        override suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> {
            calls++
            return reply(request)
        }
    }

    private object SilentLoggerFactory : LoggerFactory, Logger {
        override fun getLogger(name: String): Logger = this
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
}
