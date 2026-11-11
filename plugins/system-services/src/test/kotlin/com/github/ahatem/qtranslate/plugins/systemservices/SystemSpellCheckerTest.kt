package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.systemservices.spell.SpellingFinding
import com.github.ahatem.qtranslate.plugins.systemservices.spell.SystemSpellCheckerBackend
import com.github.ahatem.qtranslate.plugins.systemservices.spell.WindowsSpellCheckerBackend
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class SystemSpellCheckerTest {
    @Test
    fun `системный словарь использует UTF-16 и выбирает установленный регион языка`() = runBlocking {
        val backend = object : SystemSpellCheckerBackend {
            override val displayName = "Словарь для проверки"
            override suspend fun languages() = Ok(setOf("en-US", "ru-RU"))
            override suspend fun check(text: String, languageTag: String): Result<List<SpellingFinding>, ServiceError> {
                assertEquals("ru-RU", languageTag)
                return Ok(listOf(SpellingFinding(3, 6, listOf("привет"), "превет")))
            }
        }
        val service = SystemSpellCheckerService(backend, backend.languages().value(), FakePluginContext().logger)
        val result = service.check(SpellCheckRequest("🙂 превет", LanguageCode("ru"))).value()

        assertEquals("🙂 привет", result.correctedText)
        assertEquals(3, result.corrections.single().startIndex)
    }

    @Test
    fun `установленный словарь Windows возвращает правильные позиции`() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val backend = WindowsSpellCheckerBackend()
        try {
            val languages = backend.languages().value()
            val service = SystemSpellCheckerService(backend, languages, FakePluginContext().logger)
            for ((tag, word) in listOf("en" to "smple", "ru" to "превет")) {
                if (LanguageCode(tag) !in service.supportedLanguages.languages) continue
                val text = "🙂 $word"
                val corrections = service.check(SpellCheckRequest(text, LanguageCode(tag))).value().corrections
                assertTrue(corrections.any { it.startIndex == 3 && it.original == word })
            }
        } finally {
            backend.close()
        }
    }

    private fun <T> Result<T, ServiceError>.value(): T = fold(
        success = { it },
        failure = { fail(it.message) }
    )
}
