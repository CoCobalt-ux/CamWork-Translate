package com.github.ahatem.qtranslate.ui.swing.livelens

import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveLensChangeDetectorTest {
    @Test
    fun `первый снимок запускает калибровку`() {
        val detector = LiveLensChangeDetector()

        assertTrue(detector.accept(listOf(block("Hello"))).isEmpty())
        assertTrue(!detector.isCalibrated)
    }

    @Test
    fun `второй устойчивый снимок возвращает текущие сообщения`() {
        val detector = LiveLensChangeDetector()
        detector.accept(listOf(block("Hello")))

        val calibrated = detector.accept(listOf(block("Hello")))

        assertEquals(listOf("Hello"), calibrated.map(LiveLensTextBlock::text))
        assertTrue(detector.isCalibrated)
    }

    @Test
    fun `подтвержденное сообщение не отправляется повторно после краткого мерцания`() {
        val detector = LiveLensChangeDetector()
        detector.accept(listOf(block("Hello")))
        detector.accept(listOf(block("Hello")), nowMillis = 700)
        detector.accept(emptyList(), nowMillis = 1_400)

        val added = detector.accept(listOf(block("Hello")), nowMillis = 2_100)

        assertTrue(added.isEmpty())
    }

    @Test
    fun `новое сообщение требует двух наблюдений`() {
        val detector = LiveLensChangeDetector()
        detector.accept(listOf(block("Hello")))
        detector.accept(listOf(block("Hello")), nowMillis = 700)

        assertTrue(
            detector.accept(
                listOf(block("Hello"), block("What is your name?", y = 160)),
                nowMillis = 1_400
            ).isEmpty()
        )
        val added = detector.accept(
            listOf(block("Hello"), block("What is your name?", y = 160)),
            nowMillis = 2_100
        )

        assertEquals(1, added.size)
        assertEquals(160, added.single().anchor.y)
    }

    private fun block(text: String, y: Int = 100): LiveLensTextBlock =
        LiveLensTextBlock(text, Point(100, y), speaker = "BenKingX")
}
