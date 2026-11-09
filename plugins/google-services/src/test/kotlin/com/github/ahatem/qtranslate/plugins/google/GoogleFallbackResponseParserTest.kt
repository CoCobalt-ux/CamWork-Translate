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

    @Test
    fun `chrome single с одним битым предложением не возвращает обрезанный перевод`() {
        // Второе предложение без вложенного массива — форма, которую сегодняшний парсер не
        // ожидает. Результат должен провалиться целиком, а не молча отдать только "Hello ".
        val result = parseChromeTranslationResponse(
            """[[["Hello ","Привет ",null,null,10],"World"],null,"ru"]"""
        )

        assertNull(result)
    }

    @Test
    fun `batchexecute собирает все предложения одной группы, а не только первое`() {
        // Настоящий ответ translate.google.com _ TranslateWebserverUi batchexecute на
        // шестипредложенный текст (снят живым запросом, не придуман). sentenceGroups содержит
        // РОВНО ОДНУ группу, а все шесть переводов лежат внутри неё, в массиве по индексу 5 —
        // старый код брал оттуда только первый элемент (variants.firstOrNull()), поэтому длинный
        // текст всегда обрезался до первого предложения независимо от общей длины.
        val response = """)]}'

1775
[["wrb.fr","MkEWBc","[[null,null,null,[[[0,[[[null,30]],[true]]],[1,[[[null,31],[31,68]],[false,true]]],[2,[[[null,69],[69,113]],[false,true]]],[3,[[[null,114],[114,155]],[false,true]]],[4,[[[null,156],[156,205]],[false,true]]],[5,[[[null,206],[206,255]],[false,true]]]],255],null,null,[\"Привет, как твои дела сегодня? Я очень рада тебя видеть здесь снова. Ты не поверишь, что случилось сегодня утром! Мне нужно рассказать тебе кое-что важное. Надеюсь, у тебя всё хорошо и настроение отличное. Давай поговорим подольше, если у тебя есть время.\",\"ru\",\"en\",true]],[[[null,null,null,null,null,[[\"Hello, how are you doing today?\",null,null,null,null,null,\"Привет, как твои дела сегодня?\",1],[\"I'm very glad to see you here again.\",null,true,null,null,null,\"Я очень рада тебя видеть здесь снова.\",1],[\"You won't believe what happened this morning!\",null,true,null,null,null,\"Ты не поверишь, что случилось сегодня утром!\",1],[\"I need to tell you something important.\",null,true,null,null,null,\"Мне нужно рассказать тебе кое-что важное.\",1],[\"I hope you are doing well and in a great mood.\",null,true,null,null,null,\"Надеюсь, у тебя всё хорошо и настроение отличное.\",1],[\"Let's talk longer if you have time.\",null,true,null,null,null,\"Давай поговорим подольше, если у тебя есть время.\",1]],null,null,null,[]]],\"en\",1,\"ru\",[\"Привет, как твои дела сегодня? Я очень рада тебя видеть здесь снова. Ты не поверишь, что случилось сегодня утром! Мне нужно рассказать тебе кое-что важное. Надеюсь, у тебя всё хорошо и настроение отличное. Давай поговорим подольше, если у тебя есть время.\",\"ru\",\"en\",true]],\"ru\",null,null,null,null,[[[0],[[6,1]]],[[0],[[6,1]]],[[0],[[6,1]]],[[0],[[6,1]]],[[0],[[6,1]]],[[0],[[6,1]]]]]",null,null,null,"generic"]]
54
[["di",678],["af.httprm",677,"42859076959969203",3]]
26
[["e",4,null,null,2481]]
"""

        val result = parseBatchExecuteTranslationResponse(response)

        assertEquals(
            "Hello, how are you doing today?" +
                "I'm very glad to see you here again." +
                "You won't believe what happened this morning!" +
                "I need to tell you something important." +
                "I hope you are doing well and in a great mood." +
                "Let's talk longer if you have time.",
            result?.translatedText
        )
        assertEquals("ru", result?.detectedLanguage)
    }

    @Test
    fun `batchexecute с одной группой без вариантов не возвращает обрезанный перевод`() {
        val response = """)]}\'

405
[["wrb.fr","MkEWBc","[[null,null,\"ru\",[[[0,[[[null,10]],[true]]]],10],null,null,[\"Привет мир. Ещё\",\"auto\",\"en\",true]],[[[null,null,null,null,null,[[\"Hello World\",null,null,null,null,null,\"Привет мир\",1]],null,null,null,[]],[null,null,null,null,null,null,null,null,null,[]]],\"en\",1,\"ru\",[\"Привет мир. Ещё\",\"auto\",\"en\",true]],\"ru\",null,null,null,null,[[[0]]]]",null,null,null,"generic"],["di",205]]
"""

        assertNull(parseBatchExecuteTranslationResponse(response))
    }
}
