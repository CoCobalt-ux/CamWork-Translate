package com.github.ahatem.qtranslate.ui.swing.livelens

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveLensFrameCaptureTest {
    @Test
    fun `двухстрочный игровой субтитр становится одной репликой`() {
        val blocks = liveLensOcrTextBlocks(
            "Where are you going?\nWait for me!",
            Rectangle(100, 200, 800, 160)
        )

        assertEquals(1, blocks.size)
        assertEquals("Where are you going? Wait for me!", blocks.single().text)
        assertEquals(280, blocks.single().anchor.y)
    }

    @Test
    fun `имя персонажа отделяется от игрового субтитра`() {
        val block = liveLensOcrTextBlocks(
            "Geralt: Looks like rain.",
            Rectangle(0, 0, 500, 100)
        ).single()

        assertEquals("Geralt", block.speaker)
        assertEquals("Looks like rain.", block.text)
    }

    @Test
    fun `пустой OCR кадр не создаёт сообщение`() {
        assertTrue(liveLensOcrTextBlocks(" \n ", Rectangle(0, 0, 500, 100)).isEmpty())
    }
}
