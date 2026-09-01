package com.github.ahatem.qtranslate.ui.swing.livelens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveLensLayoutTest {
    @Test
    fun `перевод располагается на высоте исходного сообщения`() {
        assertEquals(
            202,
            calculateLiveLensCardY(
                anchorY = 220,
                occupiedBottom = 44,
                panelHeight = 500,
                cardHeight = 80
            )
        )
    }

    @Test
    fun `следующий перевод не перекрывает предыдущий`() {
        assertEquals(
            205,
            calculateLiveLensCardY(
                anchorY = 150,
                occupiedBottom = 200,
                panelHeight = 500,
                cardHeight = 80
            )
        )
    }

    @Test
    fun `карточка исчезает после ухода исходного текста`() {
        assertFalse(shouldRemoveLiveLensCard(now = 10_000, lastSeenAt = 0))
        assertTrue(shouldRemoveLiveLensCard(now = 10_001, lastSeenAt = 0))
    }

    @Test
    fun `видимая карточка не исчезает по возрасту`() {
        assertFalse(shouldRemoveLiveLensCard(now = 120_000, lastSeenAt = 119_000))
    }
}
