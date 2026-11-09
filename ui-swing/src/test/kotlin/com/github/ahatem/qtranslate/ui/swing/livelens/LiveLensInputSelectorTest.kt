package com.github.ahatem.qtranslate.ui.swing.livelens

import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveLensInputSelectorTest {
    @Test
    fun `игра использует видимый OCR вместо скрытого DOM`() {
        val accessibility = listOf(
            block("Main menu Settings Achievements Gallery Support"),
            block("New game Continue Load Save Exit Credits"),
            block("Privacy policy Account Notifications Store")
        )
        val visible = listOf(block("If he tries to touch me again, I am going to scream!"))

        val result = selectLiveLensInput(accessibility, visible)

        assertEquals(LiveLensInputSource.PIXEL_OCR, result.source)
        assertEquals(visible, result.messages)
    }

    @Test
    fun `обычный чат сохраняет структурированные сообщения и никнеймы`() {
        val accessibility = listOf(
            block("Hello, how are you?", speaker = "BenKingX"),
            block("I am having a great day", speaker = "Alex")
        )
        val visible = listOf(block("BenKingX Hello how are you Alex I am having a great day"))

        val result = selectLiveLensInput(accessibility, visible)

        assertEquals(LiveLensInputSource.ACCESSIBILITY, result.source)
        assertEquals(accessibility, result.messages)
    }

    @Test
    fun `пустой OCR безопасно откатывается к accessibility`() {
        val accessibility = listOf(block("Visible chat message"))

        val result = selectLiveLensInput(accessibility, emptyList())

        assertEquals(LiveLensInputSource.ACCESSIBILITY, result.source)
        assertEquals(accessibility, result.messages)
    }

    @Test
    fun `пиксельный OCR работает без accessibility`() {
        val visible = listOf(block("Only pixels are available"))

        val result = selectLiveLensInput(emptyList(), visible)

        assertEquals(LiveLensInputSource.PIXEL_OCR, result.source)
        assertEquals(visible, result.messages)
    }

    private fun block(text: String, speaker: String? = null) =
        LiveLensTextBlock(text = text, anchor = Point(10, 10), speaker = speaker)
}
