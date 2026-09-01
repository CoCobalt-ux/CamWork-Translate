package com.github.ahatem.qtranslate.ui.swing.livelens

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveLensTextReaderTest {
    @Test
    fun `оставляет только текстовые элементы и объединяет соседние дубли`() {
        val result = normalizeLiveTextCandidates(
            listOf(
                sample("  Hello   world  ", 40, 50_020),
                sample("Hello world", 55, 50_020),
                sample("Кнопка", 90, 50_000),
                sample("Second\nline", 120, 50_020)
            )
        )

        assertEquals(listOf("Hello world", "Second", "line"), result.map(LiveLensTextBlock::text))
    }

    @Test
    fun `отбрасывает заголовки Chrome и контейнеры страниц`() {
        val result = normalizeLiveTextCandidates(
            listOf(
                sample("Список моделей - Google Chrome", 40, 50_020),
                sample("Весь документ", 70, 50_030),
                sample("How are you?", 100, 50_020)
            )
        )

        assertEquals(listOf("How are you?"), result.map(LiveLensTextBlock::text))
    }

    @Test
    fun `сохраняет одинаковые сообщения из разных строк accessibility дерева`() {
        val result = normalizeLiveTextCandidates(
            listOf(
                sample("hello", 100, 50_020, Rectangle(100, 90, 60, 26)),
                sample("hello", 148, 50_020, Rectangle(100, 138, 60, 26))
            )
        )

        assertEquals(2, result.size)
        assertEquals(listOf(103, 151), result.map { it.anchor.y })
    }

    private fun sample(
        text: String,
        y: Int,
        controlType: Int,
        bounds: Rectangle? = null
    ): LiveLensRawSample = LiveLensRawSample(text, Point(100, y), controlType, bounds)
}
