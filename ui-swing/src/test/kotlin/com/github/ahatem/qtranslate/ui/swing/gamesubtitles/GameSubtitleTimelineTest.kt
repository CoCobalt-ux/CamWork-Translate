package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameSubtitleTimelineTest {
    @Test
    fun `новые реплики сохраняются отдельными строками в правильном порядке`() {
        val timeline = timeline()
        timeline.append("Первый перевод", nowMillis = 0L)
        timeline.append("Второй перевод", nowMillis = 1_000L)
        timeline.append("Третий перевод", nowMillis = 2_000L)

        assertEquals(
            listOf("Первый перевод", "Второй перевод", "Третий перевод"),
            timeline.snapshot(2_000L).map { line -> line.text }
        )
    }

    @Test
    fun `четвертая реплика вытесняет только самую старую`() {
        val timeline = timeline()
        timeline.append("Один", nowMillis = 0L)
        timeline.append("Два", nowMillis = 100L)
        timeline.append("Три", nowMillis = 200L)
        timeline.append("Четыре", nowMillis = 300L)

        assertEquals(
            listOf("Два", "Три", "Четыре"),
            timeline.snapshot(300L).map { line -> line.text }
        )
    }

    @Test
    fun `обновление незавершенной реплики не добавляет новую строку`() {
        val timeline = timeline()
        timeline.append("Подожди", nowMillis = 0L)
        timeline.append("Подожди меня", nowMillis = 200L, replaceLatest = true)

        assertEquals(listOf("Подожди меня"), timeline.snapshot(200L).map { line -> line.text })
    }

    @Test
    fun `старая реплика плавно исчезает после появления новой`() {
        val timeline = timeline()
        timeline.append("Старая", nowMillis = 0L)
        timeline.append("Новая", nowMillis = 1_000L)

        val beforeFade = timeline.snapshot(3_999L).first { line -> line.text == "Старая" }
        val duringFade = timeline.snapshot(4_500L).first { line -> line.text == "Старая" }
        assertTrue(beforeFade.opacity > duringFade.opacity)
        assertEquals(
            listOf("Новая"),
            timeline.snapshot(5_001L).map { line -> line.text }
        )
    }

    private fun timeline() = GameSubtitleTimeline(
        maxLines = 3,
        latestHoldMillis = 10_000L,
        oldHoldAfterNewMillis = 3_000L,
        fadeMillis = 1_000L
    )
}
