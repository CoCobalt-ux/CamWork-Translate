package com.github.ahatem.qtranslate.plugins.google

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoogleFallbackResponseParserTest {

    @Test
    fun `современный плоский ответ Google разбирается`() {
        val result = parseFallbackTranslationResponse("""["Bonjour"]""")

        assertEquals("Bonjour", result?.translatedText)
        assertNull(result?.detectedLanguage)
    }

    @Test
    fun `прежний вложенный ответ Google остаётся совместимым`() {
        val result = parseFallbackTranslationResponse("""[["Guten Tag", "en"]]""")

        assertEquals("Guten Tag", result?.translatedText)
        assertEquals("en", result?.detectedLanguage)
    }

    @Test
    fun `текст источника не принимается за код языка`() {
        val result = parseFallbackTranslationResponse("""[["Bonjour", "Hello world"]]""")

        assertEquals("Bonjour", result?.translatedText)
        assertNull(result?.detectedLanguage)
    }

    @Test
    fun `пустой или посторонний ответ отклоняется`() {
        assertNull(parseFallbackTranslationResponse("[]"))
        assertNull(parseFallbackTranslationResponse("{}"))
        assertNull(parseFallbackTranslationResponse("<html>Too many requests</html>"))
    }

    @Test
    fun `ответ chrome single объединяет предложения и определяет язык`() {
        val result = parseChromeTranslationResponse(
            """[[["Hello ","Привет ",null,null,10],["World","мир",null,null,10]],null,"ru"]"""
        )

        assertEquals("Hello World", result?.translatedText)
        assertEquals("ru", result?.detectedLanguage)
    }

    @Test
    fun `ответ batchexecute разбирается после защитного префикса`() {
        val response = """)]}\'

405
[["wrb.fr","MkEWBc","[[null,null,\"ru\",[[[0,[[[null,10]],[true]]]],10],null,null,[\"Привет мир\",\"auto\",\"en\",true]],[[[null,null,null,null,null,[[\"Hello World\",null,null,null,null,null,\"Привет мир\",1]],null,null,null,[]]],\"en\",1,\"ru\",[\"Привет мир\",\"auto\",\"en\",true]],\"ru\",null,null,null,null,[[[0]]]]",null,null,null,"generic"],["di",205]]
"""

        val result = parseBatchExecuteTranslationResponse(response)

        assertEquals("Hello World", result?.translatedText)
        assertEquals("ru", result?.detectedLanguage)
    }

    @Test
    fun `изменённый контракт batchexecute отклоняется`() {
        val response = """[["wrb.fr","MkEWBc","[null]",null,null,null,"generic"]]"""

        assertNull(parseBatchExecuteTranslationResponse(response))
    }
}
