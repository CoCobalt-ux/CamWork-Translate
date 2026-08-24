package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickTranslateSizingTest {

    @Test
    fun `пассивный popup всегда подстраивается под текст`() {
        assertTrue(QuickTranslateSizing.shouldAutoSize(configuredAutoSize = false, passive = true))
        assertFalse(QuickTranslateSizing.shouldAutoSize(configuredAutoSize = false, passive = false))
    }

    @Test
    fun `короткий пассивный перевод остаётся компактным`() {
        val width = QuickTranslateSizing.targetWidth(
            naturalTextWidth = 84,
            headerWidth = 286,
            maximumWidth = 460,
            passive = true
        )
        val height = QuickTranslateSizing.targetHeight(
            measuredTextHeight = 28,
            chromeHeight = 48,
            maximumHeight = 360,
            passive = true
        )

        assertEquals(QuickTranslateSizing.minimumSize(passive = true).width, width)
        assertEquals(QuickTranslateSizing.minimumSize(passive = true).height, height)
    }

    @Test
    fun `длинный перевод ограничен читаемой шириной и высотой`() {
        assertEquals(
            440,
            QuickTranslateSizing.targetWidth(
                naturalTextWidth = 1_200,
                headerWidth = 300,
                maximumWidth = 440,
                passive = true
            )
        )
        assertEquals(
            300,
            QuickTranslateSizing.targetHeight(
                measuredTextHeight = 700,
                chromeHeight = 50,
                maximumHeight = 300,
                passive = true
            )
        )
    }

    @Test
    fun `ширина строки заметно меньше половины Full HD экрана`() {
        val maximum = QuickTranslateSizing.maxDialogWidth(
            averageCharacterWidth = 7,
            screenWidth = 1920,
            passive = true
        )

        assertTrue(maximum in 320..560)
        assertTrue(maximum < 1920 / 2)
    }
}
