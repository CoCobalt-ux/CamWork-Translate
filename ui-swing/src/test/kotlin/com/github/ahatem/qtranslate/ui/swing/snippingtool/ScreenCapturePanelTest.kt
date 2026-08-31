package com.github.ahatem.qtranslate.ui.swing.snippingtool

import java.awt.Dimension
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenCapturePanelTest {
    @Test
    fun `масштабирует логическое выделение в физические пиксели HiDPI снимка`() {
        val result = mapSelectionToImage(
            selection = Rectangle(100, 200, 400, 300),
            canvasSize = Dimension(1_280, 720),
            imageSize = Dimension(1_920, 1_080)
        )

        assertEquals(Rectangle(150, 300, 600, 450), result)
    }

    @Test
    fun `полное выделение возвращает весь снимок без обрезания`() {
        val result = mapSelectionToImage(
            selection = Rectangle(0, 0, 1_707, 960),
            canvasSize = Dimension(1_707, 960),
            imageSize = Dimension(2_560, 1_440)
        )

        assertEquals(Rectangle(0, 0, 2_560, 1_440), result)
    }

    @Test
    fun `выделение ограничивается границами снимка`() {
        val result = mapSelectionToImage(
            selection = Rectangle(1_200, 650, 200, 100),
            canvasSize = Dimension(1_280, 720),
            imageSize = Dimension(1_920, 1_080)
        )

        assertEquals(Rectangle(1_800, 975, 120, 105), result)
    }
}
