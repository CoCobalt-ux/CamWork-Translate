package com.github.ahatem.qtranslate.ui.swing.main

import com.formdev.flatlaf.extras.FlatSVGIcon
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JWindow
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionTranslateButtonTest {
    @Test
    fun `кнопка располагается на малом отступе от курсора`() {
        val result = calculateSelectionButtonLocation(
            pointer = Point(100, 100),
            windowSize = Dimension(42, 42),
            screenWorkArea = Rectangle(0, 0, 1_920, 1_040),
            faceSize = 30,
            padding = 6,
            gap = 8
        )

        assertEquals(Point(102, 102), result)
        assertEquals(108, result.x + 6)
        assertEquals(108, result.y + 6)
    }

    @Test
    fun `у правого нижнего края кнопка безопасно переворачивается`() {
        val result = calculateSelectionButtonLocation(
            pointer = Point(995, 795),
            windowSize = Dimension(42, 42),
            screenWorkArea = Rectangle(0, 0, 1_000, 800),
            faceSize = 30,
            padding = 6,
            gap = 8
        )

        assertEquals(Point(951, 751), result)
        assertTrue(result.x >= 0 && result.x + 42 <= 1_000)
        assertTrue(result.y >= 0 && result.y + 42 <= 800)
    }

    @Test
    fun `отрицательный origin второго монитора не ломает clamp`() {
        val workArea = Rectangle(-1_920, 0, 1_920, 1_040)
        val result = calculateSelectionButtonLocation(
            pointer = Point(-10, 1_035),
            windowSize = Dimension(42, 42),
            screenWorkArea = workArea,
            faceSize = 30,
            padding = 6,
            gap = 8
        )

        assertTrue(result.x >= workArea.x && result.x + 42 <= workArea.x + workArea.width)
        assertTrue(result.y >= workArea.y && result.y + 42 <= workArea.y + workArea.height)
    }

    @Test
    fun `payload отдаёт точный текст только один раз и очищается`() {
        val payload = SelectionButtonPayload()
        payload.remember("selected text")

        assertEquals("selected text", payload.consume())
        assertEquals("", payload.consume())
        payload.remember("stale")
        payload.clear()
        assertEquals("", payload.consume())
    }

    /**
     * Опциональный реальный Windows smoke: создаёт JWindow и нажимает его через Robot.
     * Запуск: CAMWORK_RUN_SELECTION_BUTTON_SMOKE=true :ui-swing:test --tests *SelectionTranslateButtonTest
     */
    @Test
    fun `Windows Robot click доставляет сохранённое выделение`() {
        if (System.getenv("CAMWORK_RUN_SELECTION_BUTTON_SMOKE") != "true") return
        if (!isWindows() || GraphicsEnvironment.isHeadless()) return

        val delivered = mutableListOf<String>()
        val callback = CountDownLatch(1)
        var owner: JWindow? = null
        var button: SelectionTranslateButton? = null
        SwingUtilities.invokeAndWait {
            val testOwner = JWindow()
            owner = testOwner
            button = SelectionTranslateButton(
                owner = testOwner,
                icon = FlatSVGIcon("icons/lucide/translate.svg", 16, 16),
                tooltip = "Translate"
            ) { text ->
                delivered += text
                callback.countDown()
            }.also { it.showAt(java.awt.MouseInfo.getPointerInfo().location, "smoke payload") }
        }

        try {
            val target = button!!.bounds.let { Point(it.x + it.width / 2, it.y + it.height / 2) }
            Robot().apply {
                autoDelay = 80
                mouseMove(target.x, target.y)
                mousePress(InputEvent.BUTTON1_DOWN_MASK)
                mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            }

            assertTrue(callback.await(3, TimeUnit.SECONDS), "Mouse click не дошёл до callback")
            assertEquals(listOf("smoke payload"), delivered)
        } finally {
            SwingUtilities.invokeAndWait {
                button?.dispose()
                owner?.dispose()
            }
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}
