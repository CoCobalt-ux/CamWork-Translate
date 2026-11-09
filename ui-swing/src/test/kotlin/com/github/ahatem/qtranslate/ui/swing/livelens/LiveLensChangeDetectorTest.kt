package com.github.ahatem.qtranslate.ui.swing.livelens

import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveLensChangeDetectorTest {
    @Test
    fun `первый снимок сразу возвращает текущие сообщения`() {
        val detector = LiveLensChangeDetector()

        val added = detector.accept(listOf(block("Hello"), block("How are you?", y = 160)))

        assertEquals(listOf("Hello", "How are you?"), added.map(LiveLensTextBlock::text))
        assertTrue(detector.isCalibrated)
    }

    @Test
    fun `повторный снимок не переотправляет старые сообщения`() {
        val detector = LiveLensChangeDetector()
        detector.accept(listOf(block("Hello")))

        val added = detector.accept(listOf(block("Hello")))

        assertTrue(added.isEmpty())
    }

    @Test
    fun `пустой кадр не сбрасывает уже зафиксированный снимок`() {
        val detector = LiveLensChangeDetector()
        detector.accept(listOf(block("Hello")))
        detector.accept(emptyList())

        val added = detector.accept(listOf(block("Hello")))

        assertTrue(added.isEmpty())
    }

    @Test
    fun `прокрутка оставляет старые строки и возвращает только новый хвост`() {
        val detector = LiveLensChangeDetector()
        detector.accept(
            listOf(
                block("First", y = 80),
                block("Second", y = 120),
                block("Third", y = 160)
            )
        )
        val added = detector.accept(
            listOf(
                block("Second", y = 80),
                block("Third", y = 120),
                block("Fourth", y = 160)
            )
        )

        assertEquals(listOf("Fourth"), added.map(LiveLensTextBlock::text))
    }

    @Test
    fun `одинаковая новая реплика после старой считается отдельным сообщением`() {
        val detector = LiveLensChangeDetector()
        detector.accept(listOf(block("Hello")))

        val added = detector.accept(listOf(block("Hello"), block("Hello", y = 160)))

        assertEquals(1, added.size)
        assertEquals(160, added.single().anchor.y)
    }

    @Test
    fun `изменение одной пунктуации OCR не создает новый перевод`() {
        val detector = LiveLensChangeDetector()
        detector.accept(listOf(block("How are you?")))

        val added = detector.accept(listOf(block("How are you!")))

        assertTrue(added.isEmpty())
    }

    @Test
    fun `при полном скачке страницы берется только нижняя свежая строка`() {
        val detector = LiveLensChangeDetector()
        detector.accept(listOf(block("Old one"), block("Old two", y = 160)))

        val added = detector.accept(listOf(block("Fresh one"), block("Fresh two", y = 160)))

        assertEquals(listOf("Fresh two"), added.map(LiveLensTextBlock::text))
    }

    private fun block(text: String, y: Int = 100): LiveLensTextBlock =
        LiveLensTextBlock(text, Point(100, y), speaker = "BenKingX")
}
