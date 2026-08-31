package com.github.ahatem.qtranslate.ui.swing.livelens

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveLensMessageAssemblerTest {
    @Test
    fun `отделяет никнейм от сообщения и не отправляет никнейм переводчику`() {
        val result = assembleLiveLensMessages(
            blocks = listOf(
                block("BenKingX", x = 80, y = 120),
                block("How are you doing?", x = 175, y = 120)
            ),
            scanBounds = Rectangle(0, 0, 600, 700)
        )

        assertEquals(1, result.size)
        assertEquals("BenKingX", result.single().speaker)
        assertEquals("How are you doing?", result.single().text)
    }

    @Test
    fun `не принимает заголовки интерфейса расположенные далеко друг от друга`() {
        val result = assembleLiveLensMessages(
            blocks = listOf(
                block("Public", x = 40, y = 80),
                block("Users 0", x = 520, y = 80)
            ),
            scanBounds = Rectangle(0, 0, 600, 700)
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `распознает допустимые символы никнейма`() {
        assertTrue(isProbableNickname("Ben_King-X.2"))
        assertFalse(isProbableNickname("How are you"))
    }

    @Test
    fun `собирает перенос строки даже если прямоугольник сообщения начинается левее ника`() {
        val result = assembleLiveLensMessages(
            blocks = listOf(
                block("Hello, I'm having a great day, how are you?", x = 40, y = 120),
                block("BenKingX", x = 80, y = 120)
            ),
            scanBounds = Rectangle(0, 0, 600, 700)
        )

        assertEquals("BenKingX", result.single().speaker)
        assertEquals("Hello, I'm having a great day, how are you?", result.single().text)
    }

    @Test
    fun `связывает цветной никнейм с текстом при разной вертикальной метрике`() {
        val result = assembleLiveLensMessages(
            blocks = listOf(
                block("BenKingX", x = 80, y = 120),
                block("What's happening?", x = 175, y = 136)
            ),
            scanBounds = Rectangle(0, 0, 600, 700)
        )

        assertEquals(1, result.size)
        assertEquals("BenKingX", result.single().speaker)
        assertEquals("What's happening?", result.single().text)
    }

    @Test
    fun `не добавляет вкладки чата к сообщению рядом с верхней границей`() {
        val result = assembleLiveLensMessages(
            blocks = listOf(
                block("Public", x = 120, y = 110),
                block("Private", x = 260, y = 110),
                block("BenKingX", x = 80, y = 120),
                block("Hello", x = 175, y = 120)
            ),
            scanBounds = Rectangle(0, 0, 600, 700)
        )

        assertEquals(1, result.size)
        assertEquals("BenKingX", result.single().speaker)
        assertEquals("Hello", result.single().text)
    }

    @Test
    fun `не теряет цветной ник одинаковые строки и длинное сообщение Stripchat`() {
        val blocks = listOf(
            block("camwork", 1_537, 547), block("hello", 1_634, 546),
            block("BenKingX", 1_576, 594), block("hello", 1_694, 594),
            block("camwork", 1_537, 643), block("hello", 1_634, 642),
            block("BenKingX", 1_576, 690), block("How are you doing?", 1_694, 690),
            block("camwork", 1_537, 739), block("I'm great:", 1_634, 738),
            block("camwork", 1_537, 787), block("you are a machine", 1_634, 786),
            block("camwork", 1_537, 835), block("Why aren't you being shy?", 1_634, 834),
            block("BenKingX", 1_576, 882), block("Shit", 1_694, 882),
            block("BenKingX", 1_576, 930), block("the pamagi", 1_694, 930),
            block("camwork", 1_537, 979), block("I don't think you're working", 1_634, 978),
            block("camwork", 1_537, 1_027), block("And what did you translate?", 1_634, 1_026),
            block("Hello, I'm having a great day, how are you?", 1_537, 1_073),
            block("BenKingX", 1_576, 1_072)
        )

        val result = assembleLiveLensMessages(blocks, Rectangle(1_521, 405, 540, 870))

        assertEquals(12, result.size)
        assertEquals(2, result.count { it.speaker == "camwork" && it.text == "hello" })
        assertEquals(5, result.count { it.speaker == "BenKingX" })
        assertEquals(
            "Hello, I'm having a great day, how are you?",
            result.last().text
        )
    }


    @Test
    fun `строка без никнейма всё равно переводится`() {
        val result = assembleLiveLensMessages(
            blocks = listOf(block("How was your day?", x = 60, y = 200)),
            scanBounds = Rectangle(0, 0, 600, 700)
        )

        assertEquals(1, result.size)
        assertEquals(null, result.single().speaker)
        assertEquals("How was your day?", result.single().text)
    }

    @Test
    fun `перенос длинного сообщения склеивается с его первой строкой`() {
        val result = assembleLiveLensMessages(
            blocks = listOf(
                block("BenKingX", x = 80, y = 120),
                block("I have been waiting for you", x = 175, y = 120),
                block("since the morning", x = 175, y = 146)
            ),
            scanBounds = Rectangle(0, 0, 600, 700)
        )

        assertEquals(1, result.size)
        assertEquals("BenKingX", result.single().speaker)
        assertEquals("I have been waiting for you since the morning", result.single().text)
    }

    @Test
    fun `следующая реплика без ника не приклеивается к предыдущей`() {
        val result = assembleLiveLensMessages(
            blocks = listOf(
                block("Are you there?", x = 60, y = 200),
                block("I am waiting", x = 60, y = 260)
            ),
            scanBounds = Rectangle(0, 0, 600, 700)
        )

        assertEquals(listOf("Are you there?", "I am waiting"), result.map { it.text })
    }

    private fun block(text: String, x: Int, y: Int): LiveLensTextBlock =
        LiveLensTextBlock(text, Point(x, y))
}
