package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GameSubtitleFrameTrackerTest {
    @Test
    fun `повторяющийся игровой кадр переводится один раз`() {
        val tracker = GameSubtitleFrameTracker()

        val first = tracker.observe("Hello, how are you?")
        val repeated = tracker.observe("Hello,   how are you?")

        assertEquals(
            "Hello, how are you?",
            assertIs<GameSubtitleFrameDecision.Translate>(first).sourceText
        )
        assertIs<GameSubtitleFrameDecision.Unchanged>(repeated)
    }

    @Test
    fun `переносы субтитра объединяются в одну реплику`() {
        val tracker = GameSubtitleFrameTracker()

        val decision = tracker.observe("If he tries to touch me again,\nI am going to scream!")

        assertEquals(
            "If he tries to touch me again, I am going to scream!",
            assertIs<GameSubtitleFrameDecision.Translate>(decision).sourceText
        )
    }

    @Test
    fun `краткий пустой кадр не стирает уже показанный перевод`() {
        val tracker = GameSubtitleFrameTracker(emptyFramesBeforeClear = 3)
        tracker.observe("A visible subtitle")

        assertIs<GameSubtitleFrameDecision.Unchanged>(tracker.observe(""))
        assertIs<GameSubtitleFrameDecision.Unchanged>(tracker.observe("  "))
        assertIs<GameSubtitleFrameDecision.Clear>(tracker.observe(null))
    }

    @Test
    fun `новая реплика немедленно заменяет предыдущую`() {
        val tracker = GameSubtitleFrameTracker()
        tracker.observe("First subtitle")

        val changed = tracker.observe("Second subtitle")

        assertEquals(
            "Second subtitle",
            assertIs<GameSubtitleFrameDecision.Translate>(changed).sourceText
        )
    }

    @Test
    fun `одна ошибка OCR не запускает повторный сетевой перевод`() {
        val tracker = GameSubtitleFrameTracker()
        tracker.observe("If he tries to touch me again, I will scream!")

        val noisyFrame = tracker.observe("If he tries to touch me agaln, I will scream!")

        assertIs<GameSubtitleFrameDecision.Unchanged>(noisyFrame)
    }
}
