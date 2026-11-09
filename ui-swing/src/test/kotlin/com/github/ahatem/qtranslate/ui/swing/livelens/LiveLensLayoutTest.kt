package com.github.ahatem.qtranslate.ui.swing.livelens

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveLensLayoutTest {
    @Test
    fun `готовые сообщения прижимаются к низу в исходном порядке`() {
        assertEquals(
            listOf(
                LiveLensCardPlacement(0, 238),
                LiveLensCardPlacement(1, 323),
                LiveLensCardPlacement(2, 408)
            ),
            calculateLiveLensFeedPlacements(
                cardHeights = listOf(80, 80, 80),
                panelHeight = 500,
            )
        )
    }

    @Test
    fun `новое сообщение появляется снизу и выталкивает старое вверх`() {
        assertEquals(
            listOf(
                LiveLensCardPlacement(2, 53),
                LiveLensCardPlacement(3, 108)
            ),
            calculateLiveLensFeedPlacements(
                cardHeights = listOf(50, 50, 50, 50),
                panelHeight = 170
            )
        )
    }

    @Test
    fun `слишком высокая старая карточка не скрывает свежий перевод`() {
        assertEquals(
            listOf(LiveLensCardPlacement(2, 128)),
            calculateLiveLensFeedPlacements(
                cardHeights = listOf(200, 200, 80),
                panelHeight = 220
            )
        )
    }
}
