package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameSubtitleLayoutTest {
    @Test
    fun `между репликами остаётся явный визуальный интервал`() {
        val heights = listOf(48, 64, 48)
        val gap = GAME_SUBTITLE_CAPTION_GAP

        val placements = calculateGameSubtitleVerticalPlacements(
            boxHeights = heights,
            panelHeight = 300,
            topInset = 8,
            bottomInset = 14,
            gap = gap
        )

        placements.zipWithNext().forEach { (upper, lower) ->
            assertEquals(gap, lower.y - (upper.y + heights[upper.lineIndex]))
        }
        assertTrue(gap >= 16)
        assertEquals(300 - 14 - heights.last(), placements.last().y)
    }

    @Test
    fun `при нехватке места новейшая реплика всегда остаётся видимой`() {
        val placements = calculateGameSubtitleVerticalPlacements(
            boxHeights = listOf(100, 100, 100),
            panelHeight = 150,
            topInset = 8,
            bottomInset = 14,
            gap = 18
        )

        assertEquals(listOf(GameSubtitleVerticalPlacement(lineIndex = 2, y = 36)), placements)
    }
}
