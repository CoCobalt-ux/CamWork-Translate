package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AIServiceRecoveryTest {
    @Test
    fun truncatedTranslationIsRejectedAndNextRequestSucceeds() = runTest {
        val responses = ArrayDeque(listOf(reply("length", "Частичный конфиденциальный текст"), reply("stop", "Полный перевод")))
        val client = client { Ok(responses.removeFirst()) }
        val translator = AITranslatorService(client)
        val request = TranslationRequest("Исходный текст", LanguageCode.ENGLISH, LanguageCode.RUSSIAN)
        val error = translator.translate(request).fold({ error("Неполный перевод не должен считаться успешным") }, { it })
        assertIs<ServiceError.InvalidResponseError>(error)
        assertFalse(error.message.contains("конфиденциальный"))
        assertEquals("Полный перевод", translator.translate(request).fold({ it.translatedText }, { error(it.message) }))
    }

    @Test
    fun visionRejectsTruncationAndCanRecover() = runTest {
        val responses = ArrayDeque(listOf(reply("length"), reply("stop", "Текст изображения")))
        val client = client { Ok(responses.removeFirst()) }
        val image = ImageData(byteArrayOf(1), "png", 1, 1)
        assertTrue(client.completeWithImage("Тест", image).isErr)
        assertEquals("Текст изображения", client.completeWithImage("Тест", image).fold({ it }, { error(it.message) }))
    }

    @Test
    fun refusalToolCallAndBlankResponseCannotReplaceSource() = runTest {
        val responses = ArrayDeque(listOf(reply("content_filter"), reply("tool_calls"), reply("stop", " "), reply(null, "Совместимый ответ")))
        val client = client { Ok(responses.removeFirst()) }
        repeat(3) { assertTrue(client.complete("Тест", "Текст").isErr) }
        assertEquals("Совместимый ответ", client.complete("Тест", "Текст").fold({ it }, { error(it.message) }))
    }

    @Test
    fun cancelledCompletionDoesNotPoisonLaterRequests() = runTest {
        var first = true
        val client = client {
            if (first) { first = false; awaitCancellation() }
            Ok(reply("stop", "Восстановлено"))
        }
        assertNull(withTimeoutOrNull(50) { client.complete("Тест", "Отмена") })
        assertEquals("Восстановлено", client.complete("Тест", "Повтор").fold({ it }, { error(it.message) }))
    }

    @Test
    fun concurrentCompletionsKeepFailuresIsolated() = runTest {
        val calls = AtomicInteger()
        val client = client { Ok(reply(if (calls.incrementAndGet() % 5 == 0) "length" else "stop")) }
        val results = List(200) { async { client.complete("Тест", "Текст") } }.awaitAll()
        assertEquals(160, results.count { it.isOk })
        assertEquals(40, results.count { it.isErr })
        assertTrue(client.complete("Тест", "Последний").isOk)
    }

    private fun client(response: suspend () -> Result<String, ServiceError>): AIServiceClient {
        val http = object : TextHttpClient() {
            override suspend fun get(url: String, headers: Map<String, String>, queryParams: Map<String, Any?>): Result<String, ServiceError> = error("GET не ожидается")
            override suspend fun post(url: String, headers: Map<String, String>, body: String?, queryParams: Map<String, Any?>): Result<String, ServiceError> = response()
        }
        return AIServiceClient(FakePluginContext(httpClient = http), http) { AISettings(baseUrl = "http://localhost:1/v1") }
    }

    private fun reply(reason: String?, text: String = "Тестовый ответ") = Json.encodeToString(
        ChatCompletionResponse(listOf(ChatChoice(ChatMessage("assistant", text), reason)))
    )
}
