package com.github.ahatem.qtranslate.ui.swing.livelens

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import javax.swing.SwingUtilities
import kotlin.test.assertEquals

class LiveLensWindowTest {
    private var window: LiveLensWindow? = null

    @Test
    fun `преобразует физические координаты второго монитора в локальные координаты рамки`() {
        val result = mapNativePointToLocal(
            point = Point(2_900, -1_350),
            nativeBounds = Rectangle(2_700, -1_600, 1_000, 900),
            localSize = Dimension(500, 450)
        )

        assertEquals(Point(100, 125), result)
    }

    @AfterEach
    fun tearDown() {
        window?.let { current ->
            SwingUtilities.invokeAndWait { current.dispose() }
        }
    }

    @Test
    fun `окно настройки создается и отображается`() {
        assumeFalse(GraphicsEnvironment.isHeadless())

        SwingUtilities.invokeAndWait {
            window = LiveLensWindow(
                strings = LiveLensStrings(
                    title = "LIVE-перевод",
                    setupHint = "Выберите область",
                    start = "Старт",
                    pause = "Пауза",
                    edit = "Изменить",
                    close = "Закрыть",
                    watching = "Наблюдение",
                    reading = "Чтение",
                    translating = "Перевод",
                    noText = "Текст не найден",
                    failed = "Ошибка"
                ),
                onStart = { _, _ -> },
                onPause = {},
                onClose = {},
                onBoundsChanged = {}
            ).also { it.showSetup(Rectangle(120, 120, 620, 420)) }
        }

        assertTrue(window != null)
    }
}
