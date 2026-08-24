package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.Color
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrayInteractionPolicyTest {
    @Test
    fun `одиночный левый клик ожидает интервал двойного клика`() {
        assertEquals(
            TrayClickDecision.SCHEDULE_TOGGLE,
            TrayInteractionPolicy.decide(MouseEvent.BUTTON1, clickCount = 1, isPopupTrigger = false)
        )
    }

    @Test
    fun `второй левый клик отменяет toggle и открывает окно`() {
        assertEquals(
            TrayClickDecision.CANCEL_TOGGLE_AND_OPEN,
            TrayInteractionPolicy.decide(MouseEvent.BUTTON1, clickCount = 2, isPopupTrigger = false)
        )
    }

    @Test
    fun `правый и popup клики не меняют настройку`() {
        assertEquals(
            TrayClickDecision.IGNORE,
            TrayInteractionPolicy.decide(MouseEvent.BUTTON3, clickCount = 1, isPopupTrigger = true)
        )
        assertEquals(
            TrayClickDecision.IGNORE,
            TrayInteractionPolicy.decide(MouseEvent.BUTTON3, clickCount = 1, isPopupTrigger = false)
        )
    }

    @Test
    fun `интервал двойного клика берётся из настройки системы`() {
        assertEquals(650, TrayInteractionPolicy.doubleClickDelayMs(650))
        assertEquals(500, TrayInteractionPolicy.doubleClickDelayMs(null))
        assertEquals(500, TrayInteractionPolicy.doubleClickDelayMs(0))
    }

    @Test
    fun `повторный toggle возвращает исходное состояние`() {
        val enabled = TrayInteractionPolicy.toggledAutoSelectionState(false)
        val disabledAgain = TrayInteractionPolicy.toggledAutoSelectionState(enabled)

        assertTrue(enabled)
        assertEquals(false, disabledAgain)
    }

    @Test
    fun `зелёный tray требует одновременно auto и global master`() {
        assertEquals(false, TrayInteractionPolicy.isAutoSelectionEffective(false, true))
        assertEquals(false, TrayInteractionPolicy.isAutoSelectionEffective(true, false))
        assertEquals(true, TrayInteractionPolicy.isAutoSelectionEffective(true, true))
    }

    @Test
    fun `active и neutral иконки сохраняют DPI варианты`() {
        val images = TrayIconImageFactory.create(
            listOf(solidImage(16, Color(20, 100, 220)), solidImage(32, Color(20, 100, 220)))
        )

        val neutralVariants = (images.neutral as MultiResolutionImage).resolutionVariants
        val activeVariants = (images.active as MultiResolutionImage).resolutionVariants

        assertEquals(listOf(16, 32), neutralVariants.map { it.getWidth(null) })
        assertEquals(listOf(16, 32), activeVariants.map { it.getWidth(null) })

        val neutralPixel = Color((neutralVariants.last() as BufferedImage).getRGB(5, 5), true)
        assertEquals(neutralPixel.red, neutralPixel.green)
        assertEquals(neutralPixel.green, neutralPixel.blue)

        val activePixel = Color((activeVariants.last() as BufferedImage).getRGB(26, 26), true)
        assertTrue(activePixel.green > activePixel.red)
        assertTrue(activePixel.green > activePixel.blue)
    }

    private fun solidImage(size: Int, color: Color): BufferedImage =
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().also { graphics ->
                try {
                    graphics.color = color
                    graphics.fillRect(0, 0, size, size)
                } finally {
                    graphics.dispose()
                }
            }
        }
}
