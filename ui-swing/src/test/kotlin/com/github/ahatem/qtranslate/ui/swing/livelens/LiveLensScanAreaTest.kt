package com.github.ahatem.qtranslate.ui.swing.livelens

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveLensScanAreaTest {
    private val frame = Rectangle(1_500, 400, 500, 800)

    @Test
    fun `сообщение целиком внутри рамки принимается`() {
        val message = Rectangle(1_600, 600, 260, 40)

        assertTrue(isMostlyInsideScanArea(message, frame))
    }

    @Test
    fun `сообщение у самого края рамки всё ещё принимается`() {
        val message = Rectangle(1_480, 600, 260, 40)

        assertTrue(isMostlyInsideScanArea(message, frame))
    }

    @Test
    fun `прокручиваемый контейнер чата задевающий рамку одним краем отбрасывается`() {
        // Тот самый случай из бага: FindAll возвращает контейнер на всю страницу, который
        // лишь секунду пересекает нижний край маленькой рамки.
        val wholePageContainer = Rectangle(1_500, -4_000, 500, 5_000)

        assertFalse(isMostlyInsideScanArea(wholePageContainer, frame))
    }

    @Test
    fun `контейнер шире экрана касающийся рамки узкой полосой отбрасывается`() {
        val fullWidthBanner = Rectangle(0, 390, 3_840, 60)

        assertFalse(isMostlyInsideScanArea(fullWidthBanner, frame))
    }

    @Test
    fun `вырожденный прямоугольник нулевой площади не проходит`() {
        assertFalse(isMostlyInsideScanArea(Rectangle(1_600, 600, 0, 40), frame))
    }

    @Test
    fun `прямоугольник без пересечения отбрасывается`() {
        assertFalse(isMostlyInsideScanArea(Rectangle(0, 0, 100, 100), frame))
    }
}
