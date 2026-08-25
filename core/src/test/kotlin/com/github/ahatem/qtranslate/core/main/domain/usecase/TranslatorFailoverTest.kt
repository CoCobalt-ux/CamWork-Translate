package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TranslatorFailoverTest {

    @Test
    fun `после полного отказа Google первым используется Bing`() = runTest {
        val google = FakeTranslator(
            key = TranslatorFailover.GOOGLE_TRANSLATOR_KEY,
            result = Err(ServiceError.RateLimitError("Google exhausted"))
        )
        val bing = FakeTranslator(
            key = TranslatorFailover.BING_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Hello", LanguageCode.RUSSIAN))
        )
        val deepL = FakeTranslator(
            key = TranslatorFailover.DEEPL_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Other"))
        )
        // Порядок в registry не определяет маршрут: Bing всё равно должен быть первым резервом.
        val manager = manager(google, deepL, bing)

        val execution = TranslatorFailover(manager).translate(
            manager.getActive<Translator>(ServiceRole.TRANSLATOR)!!,
            request()
        )

        assertEquals("bing", execution.translatorId)
        assertEquals("Hello", execution.result.success().translatedText)
        assertEquals(1, google.calls)
        assertEquals(1, bing.calls)
        assertEquals(0, deepL.calls)
    }

    @Test
    fun `после ошибки Bing используется DeepL`() = runTest {
        val google = FakeTranslator(
            key = TranslatorFailover.GOOGLE_TRANSLATOR_KEY,
            result = Err(ServiceError.ServiceUnavailableError("Google unavailable"))
        )
        val bing = FakeTranslator(
            key = TranslatorFailover.BING_TRANSLATOR_KEY,
            result = Err(ServiceError.NetworkError("Bing unavailable"))
        )
        val deepL = FakeTranslator(
            key = TranslatorFailover.DEEPL_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Hello", LanguageCode.RUSSIAN))
        )
        val manager = manager(google, deepL, bing)

        val execution = TranslatorFailover(manager).translate(
            manager.getActive<Translator>(ServiceRole.TRANSLATOR)!!,
            request()
        )

        assertEquals("deepl", execution.translatorId)
        assertEquals("Hello", execution.result.success().translatedText)
        assertEquals(1, google.calls)
        assertEquals(1, bing.calls)
        assertEquals(1, deepL.calls)
    }

    @Test
    fun `зависший Bing ограничен по времени и не блокирует DeepL`() = runTest {
        val google = FakeTranslator(
            key = TranslatorFailover.GOOGLE_TRANSLATOR_KEY,
            result = Err(ServiceError.ServiceUnavailableError("Google unavailable"))
        )
        val bing = FakeTranslator(
            key = TranslatorFailover.BING_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Late")),
            delayMillis = Long.MAX_VALUE
        )
        val deepL = FakeTranslator(
            key = TranslatorFailover.DEEPL_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Hello"))
        )
        val manager = manager(google, bing, deepL)

        val execution = TranslatorFailover(manager).translate(
            manager.getActive<Translator>(ServiceRole.TRANSLATOR)!!,
            request()
        )

        assertEquals("deepl", execution.translatorId)
        assertEquals("Hello", execution.result.success().translatedText)
        assertEquals(TranslatorFailover.BING_BASE_TIMEOUT_MS, testScheduler.currentTime)
        assertEquals(1, bing.calls)
        assertEquals(1, deepL.calls)
    }

    @Test
    fun `длинный Google получает увеличенный deadline перед переходом к Bing`() = runTest {
        val google = FakeTranslator(
            key = TranslatorFailover.GOOGLE_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Late")),
            delayMillis = Long.MAX_VALUE
        )
        val bing = FakeTranslator(
            key = TranslatorFailover.BING_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Hello"))
        )
        val manager = manager(google, bing)
        val longRequest = request().copy(text = "Я".repeat(9_001))

        val execution = TranslatorFailover(manager).translate(
            manager.getActive<Translator>(ServiceRole.TRANSLATOR)!!,
            longRequest
        )

        val expectedGoogleDeadline = TranslatorFailover.GOOGLE_BASE_TIMEOUT_MS +
            2 * TranslatorFailover.GOOGLE_TIMEOUT_PER_ADDITIONAL_CHUNK_MS
        assertEquals(expectedGoogleDeadline, testScheduler.currentTime)
        assertEquals(TranslatorFailover.BING_TRANSLATOR_KEY, execution.translator.key)
        assertEquals("Hello", execution.result.success().translatedText)
    }

    @Test
    fun `длинный Bing получает deadline по числу блоков но не превышает максимум`() = runTest {
        val google = FakeTranslator(
            key = TranslatorFailover.GOOGLE_TRANSLATOR_KEY,
            result = Err(ServiceError.ServiceUnavailableError("Google unavailable"))
        )
        val bing = FakeTranslator(
            key = TranslatorFailover.BING_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Late")),
            delayMillis = Long.MAX_VALUE
        )
        val deepL = FakeTranslator(
            key = TranslatorFailover.DEEPL_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Hello"))
        )
        val manager = manager(google, bing, deepL)
        val longRequest = request().copy(text = "Я".repeat(50_000))

        val execution = TranslatorFailover(manager).translate(
            manager.getActive<Translator>(ServiceRole.TRANSLATOR)!!,
            longRequest
        )

        assertEquals(TranslatorFailover.BING_MAX_ATTEMPT_TIMEOUT_MS, testScheduler.currentTime)
        assertEquals(TranslatorFailover.DEEPL_TRANSLATOR_KEY, execution.translator.key)
        assertEquals("Hello", execution.result.success().translatedText)
    }

    @Test
    fun `общий deadline оставляет третьему fallback время на успех`() = runTest {
        val google = FakeTranslator(
            key = TranslatorFailover.GOOGLE_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Late Google")),
            delayMillis = Long.MAX_VALUE
        )
        val bing = FakeTranslator(
            key = TranslatorFailover.BING_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Late Bing")),
            delayMillis = Long.MAX_VALUE
        )
        val deepL = FakeTranslator(
            key = TranslatorFailover.DEEPL_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Hello")),
            delayMillis = 1_000
        )
        val manager = manager(google, bing, deepL)
        val failover = TranslatorFailover(
            activeServiceManager = manager,
            monotonicMillis = { testScheduler.currentTime }
        )

        val execution = failover.translate(
            active = manager.getActive<Translator>(ServiceRole.TRANSLATOR)!!,
            request = request().copy(text = "Я".repeat(50_000)),
            totalTimeoutMillis = 30_000
        )

        assertEquals(TranslatorFailover.DEEPL_TRANSLATOR_KEY, execution.translator.key)
        assertEquals("Hello", execution.result.success().translatedText)
        assertEquals(1, google.calls)
        assertEquals(1, bing.calls)
        assertEquals(1, deepL.calls)
        assertTrue(testScheduler.currentTime < 30_000)
    }

    @Test
    fun `после ошибки выбранного Bing используется доступный Google без повторного Bing`() = runTest {
        val bing = FakeTranslator(
            key = TranslatorFailover.BING_TRANSLATOR_KEY,
            result = Err(ServiceError.AuthenticationError("expired session"))
        )
        val google = FakeTranslator(
            key = TranslatorFailover.GOOGLE_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Hello"))
        )
        val deepL = FakeTranslator(
            key = TranslatorFailover.DEEPL_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Other"))
        )
        val manager = manager(bing, deepL, google)

        val execution = TranslatorFailover(manager).translate(
            manager.getActive<Translator>(ServiceRole.TRANSLATOR)!!,
            request()
        )

        assertEquals(TranslatorFailover.GOOGLE_TRANSLATOR_KEY, execution.translator.key)
        assertEquals("Hello", execution.result.success().translatedText)
        assertEquals(1, bing.calls)
        assertEquals(1, google.calls)
        assertEquals(0, deepL.calls)
    }

    @Test
    fun `неожиданное исключение провайдера не обрывает резервную цепочку`() = runTest {
        val google = FakeTranslator(
            key = TranslatorFailover.GOOGLE_TRANSLATOR_KEY,
            result = Err(ServiceError.UnknownError("unused")),
            throwable = IllegalStateException("broken parser")
        )
        val bing = FakeTranslator(
            key = TranslatorFailover.BING_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Hello"))
        )
        val manager = manager(google, bing)

        val execution = TranslatorFailover(manager).translate(
            manager.getActive<Translator>(ServiceRole.TRANSLATOR)!!,
            request()
        )

        assertEquals(TranslatorFailover.BING_TRANSLATOR_KEY, execution.translator.key)
        assertEquals(1, google.calls)
        assertEquals(1, bing.calls)
    }

    @Test
    fun `отмена вызывающей coroutine не превращается в ошибку и не запускает fallback`() = runTest {
        val google = FakeTranslator(
            key = TranslatorFailover.GOOGLE_TRANSLATOR_KEY,
            result = Err(ServiceError.UnknownError("unused")),
            throwable = CancellationException("cancelled")
        )
        val bing = FakeTranslator(
            key = TranslatorFailover.BING_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Hello"))
        )
        val manager = manager(google, bing)

        assertFailsWith<CancellationException> {
            TranslatorFailover(manager).translate(
                manager.getActive<Translator>(ServiceRole.TRANSLATOR)!!,
                request()
            )
        }
        assertEquals(0, bing.calls)
    }

    @Test
    fun `успешный Google не вызывает DeepL`() = runTest {
        val google = FakeTranslator(
            key = TranslatorFailover.GOOGLE_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Hello"))
        )
        val deepL = FakeTranslator(
            key = TranslatorFailover.DEEPL_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Other"))
        )
        val manager = manager(google, deepL)

        val execution = TranslatorFailover(manager).translate(
            manager.getActive<Translator>(ServiceRole.TRANSLATOR)!!,
            request()
        )

        assertEquals("google", execution.translatorId)
        assertEquals("Hello", execution.result.success().translatedText)
        assertEquals(0, deepL.calls)
    }

    @Test
    fun `ошибка выбранного не Google сервиса не меняет выбор пользователя`() = runTest {
        val selected = FakeTranslator(
            key = "custom-translator",
            result = Err(ServiceError.NetworkError("offline"))
        )
        val deepL = FakeTranslator(
            key = TranslatorFailover.DEEPL_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Other"))
        )
        val manager = manager(selected, deepL)

        val execution = TranslatorFailover(manager).translate(
            manager.getActive<Translator>(ServiceRole.TRANSLATOR)!!,
            request()
        )

        assertEquals("google", execution.translatorId)
        assertIs<ServiceError.NetworkError>(execution.result.failure())
        assertEquals(0, deepL.calls)
    }

    @Test
    fun `отключённый Bing пропускается и используется DeepL`() = runTest {
        val google = FakeTranslator(
            key = TranslatorFailover.GOOGLE_TRANSLATOR_KEY,
            result = Err(ServiceError.RateLimitError("Google exhausted"))
        )
        val bing = FakeTranslator(
            key = TranslatorFailover.BING_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("Bing"))
        )
        val deepL = FakeTranslator(
            key = TranslatorFailover.DEEPL_TRANSLATOR_KEY,
            result = Ok(TranslationResponse("DeepL"))
        )
        val manager = manager(google, bing, deepL, disabled = setOf("bing"))

        val execution = TranslatorFailover(manager).translate(
            manager.getActive<Translator>(ServiceRole.TRANSLATOR)!!,
            request()
        )

        assertEquals("deepl", execution.translatorId)
        assertEquals("DeepL", execution.result.success().translatedText)
        assertEquals(0, bing.calls)
        assertEquals(1, deepL.calls)
    }

    private fun manager(
        selected: Translator,
        vararg fallbacks: Translator,
        disabled: Set<String> = emptySet()
    ): ActiveServiceManager {
        val preset = ServicePreset(
            id = "preset",
            name = "test",
            selectedServices = mapOf(ServiceRole.TRANSLATOR to "google")
        )
        val services = linkedMapOf<String, Service>("google" to selected)
        fallbacks.forEach { translator ->
            val id = when (translator.key) {
                TranslatorFailover.BING_TRANSLATOR_KEY -> "bing"
                TranslatorFailover.DEEPL_TRANSLATOR_KEY -> "deepl"
                else -> "fallback-${services.size}"
            }
            services[id] = translator
        }
        return ActiveServiceManager(
            activeServices = MutableStateFlow(services),
            configuration = MutableStateFlow(
                Configuration(
                    servicePresets = listOf(preset),
                    activeServicePresetId = preset.id,
                    disabledServices = disabled
                )
            )
        )
    }

    private fun request() = TranslationRequest(
        text = "Привет",
        sourceLanguage = LanguageCode.AUTO,
        targetLanguage = LanguageCode.ENGLISH
    )

    private fun <T> Result<T, ServiceError>.success(): T = fold(
        success = { it },
        failure = { error("Ожидался успех: ${it.message}") }
    )

    private fun <T> Result<T, ServiceError>.failure(): ServiceError = fold(
        success = { error("Ожидалась ошибка: $it") },
        failure = { it }
    )
}

private class FakeTranslator(
    override val key: String,
    private val result: Result<TranslationResponse, ServiceError>,
    private val delayMillis: Long = 0,
    private val throwable: Exception? = null
) : Translator {
    override val name: String = key
    override val version: String = "test"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All
    var calls: Int = 0
        private set

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        calls++
        throwable?.let { throw it }
        if (delayMillis > 0) delay(delayMillis)
        return result
    }
}
