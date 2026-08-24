package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.math.abs

class LoadingIndicatorTest {

    @Test
    fun `загрузка не скрывается по короткому таймеру`() {
        assertNull(LoadingIndicatorTiming.autoHideDelay(LoadingIndicatorPhase.TRANSLATING))
    }

    @Test
    fun `успех показывается короче ошибки`() {
        val success = LoadingIndicatorTiming.autoHideDelay(LoadingIndicatorPhase.SUCCESS)
        val error = LoadingIndicatorTiming.autoHideDelay(LoadingIndicatorPhase.ERROR)

        assertTrue(checkNotNull(success) < checkNotNull(error))
    }

    @Test
    fun `состояние по умолчанию означает перевод`() {
        val state = LoadingIndicatorState(isVisible = true, message = "Перевод…")

        assertEquals(LoadingIndicatorPhase.TRANSLATING, state.phase)
        assertEquals("Перевод…", state.message)
    }

    @Test
    fun `успех получает компактную явную галочку`() {
        assertEquals(
            LoadingIndicatorMark.CHECK,
            LoadingIndicatorVisuals.markFor(LoadingIndicatorPhase.SUCCESS)
        )
        assertTrue(LoadingIndicatorVisuals.MARKER_SIZE <= 16)
    }

    @Test
    fun `анимация перевода не получает терминальный маркер`() {
        assertEquals(
            LoadingIndicatorMark.NONE,
            LoadingIndicatorVisuals.markFor(LoadingIndicatorPhase.TRANSLATING)
        )
    }

    @Test
    fun `длинный текст toast сокращается по фактической ширине`() {
        val compact = LoadingIndicatorVisuals.displayMessage(
            message = "Очень длинное сообщение об ошибке",
            maxWidth = 80,
            measure = { it.length * 8 }
        )

        assertTrue(compact.endsWith("…"))
        assertTrue(compact.length * 8 <= 80)
    }

    @Test
    fun `иконка и текст не пересекаются и центрированы на 100 и 200 процентах`() {
        listOf(1, 2).forEach { factor ->
            val metrics = LoadingIndicatorGeometry.metrics { it * factor }
            val pulse = JPanel()
            val marker = JPanel()
            val message = JLabel("Перевод вставлен").apply {
                preferredSize = Dimension(112 * factor, 18 * factor)
            }
            val surface = createLoadingIndicatorSurface(pulse, marker, message, metrics).apply {
                border = BorderFactory.createLineBorder(java.awt.Color.GRAY, 1, true)
                size = LoadingIndicatorGeometry.preferredSize(message.preferredSize, metrics, borderWidth = 1)
                doLayout()
            }
            val iconSlot = surface.components[0]

            assertEquals(
                LoadingIndicatorGeometry.preferredSize(message.preferredSize, metrics, borderWidth = 1),
                surface.preferredSize
            )
            assertEquals(metrics.gap, message.x - (iconSlot.x + iconSlot.width))
            assertEquals(metrics.horizontalInset, iconSlot.x - 1)
            assertEquals(
                metrics.horizontalInset,
                surface.width - 1 - (message.x + message.width)
            )
            assertTrue(
                abs((iconSlot.y + iconSlot.height / 2) - (message.y + message.height / 2)) <= 1
            )
        }
    }
}
