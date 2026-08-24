package com.github.ahatem.qtranslate.ui.swing.main

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShiftTapTranslateGestureTest {
    private val shift = 42
    private val letter = 30
    private val windows = 3675
    private val control = 29
    private val selectAll = 65

    @Test
    fun `короткий Shift после выделения запускает перевод`() {
        val gesture = ShiftTapTranslateGesture(shift)

        gesture.onSelectionCompleted(1_000)
        gesture.onKeyPressed(shift, 1_200)

        assertTrue(gesture.onKeyReleased(shift, 1_320))
    }

    @Test
    fun `Shift без свежего выделения игнорируется`() {
        val gesture = ShiftTapTranslateGesture(shift)

        gesture.onKeyPressed(shift, 1_000)

        assertFalse(gesture.onKeyReleased(shift, 1_100))
    }

    @Test
    fun `двойной клик позволяет перевести выделенное слово`() {
        val gesture = ShiftTapTranslateGesture(shift)

        gesture.onPointerPressed()
        gesture.onPointerClicked(clickCount = 2, nowMs = 1_000)
        gesture.onKeyPressed(shift, 1_100)

        assertTrue(gesture.onKeyReleased(shift, 1_200))
    }

    @Test
    fun `одиночный клик не считается выделением`() {
        val gesture = ShiftTapTranslateGesture(shift)

        gesture.onPointerPressed()
        gesture.onPointerClicked(clickCount = 1, nowMs = 1_000)
        gesture.onKeyPressed(shift, 1_100)

        assertFalse(gesture.onKeyReleased(shift, 1_200))
    }

    @Test
    fun `Shift с буквой не считается одиночным жестом`() {
        val gesture = ShiftTapTranslateGesture(shift)

        gesture.onSelectionCompleted(1_000)
        gesture.onKeyPressed(shift, 1_100)
        gesture.onKeyPressed(letter, 1_120)
        gesture.onKeyReleased(letter, 1_160)

        assertFalse(gesture.onKeyReleased(shift, 1_180))
    }

    @Test
    fun `Win Shift S не запускает перевод`() {
        val gesture = ShiftTapTranslateGesture(shift)

        gesture.onSelectionCompleted(1_000)
        gesture.onKeyPressed(windows, 1_100)
        gesture.onKeyPressed(shift, 1_120)
        gesture.onKeyPressed(letter, 1_140)
        gesture.onKeyReleased(letter, 1_180)
        gesture.onKeyReleased(shift, 1_200)

        assertFalse(gesture.onKeyReleased(windows, 1_220))
    }

    @Test
    fun `удерживаемый Shift игнорируется`() {
        val gesture = ShiftTapTranslateGesture(shift)

        gesture.onSelectionCompleted(1_000)
        gesture.onKeyPressed(shift, 1_100)

        assertFalse(gesture.onKeyReleased(shift, 1_500))
    }

    @Test
    fun `устаревшее выделение игнорируется`() {
        val gesture = ShiftTapTranslateGesture(shift)

        gesture.onSelectionCompleted(1_000)
        gesture.onKeyPressed(shift, 5_100)

        assertFalse(gesture.onKeyReleased(shift, 5_200))
    }

    @Test
    fun `клик после выделения отменяет ожидающий жест`() {
        val gesture = ShiftTapTranslateGesture(shift)

        gesture.onSelectionCompleted(1_000)
        gesture.onPointerPressed()
        gesture.onKeyPressed(shift, 1_200)

        assertFalse(gesture.onKeyReleased(shift, 1_300))
    }

    @Test
    fun `перетаскивание с зажатым Shift игнорируется`() {
        val gesture = ShiftTapTranslateGesture(shift)

        gesture.onKeyPressed(shift, 1_000)
        gesture.onPointerPressed()
        gesture.onPointerDragged()
        gesture.onSelectionCompleted(1_100)

        assertFalse(gesture.onKeyReleased(shift, 1_200))
    }

    @Test
    fun `одно выделение можно использовать только один раз`() {
        val gesture = ShiftTapTranslateGesture(shift)

        gesture.onSelectionCompleted(1_000)
        gesture.onKeyPressed(shift, 1_100)
        assertTrue(gesture.onKeyReleased(shift, 1_200))

        gesture.onKeyPressed(shift, 2_000)
        assertFalse(gesture.onKeyReleased(shift, 2_100))
    }

    @Test
    fun `Ctrl A создаёт выделение для следующего отдельного Shift`() {
        val gesture = ShiftTapTranslateGesture(
            triggerKeyCode = shift,
            selectionModifierKeyCodes = setOf(control),
            selectAllKeyCode = selectAll
        )

        gesture.onKeyPressed(control, 1_000)
        gesture.onKeyPressed(selectAll, 1_020)
        gesture.onKeyReleased(selectAll, 1_040)
        gesture.onKeyReleased(control, 1_060)
        gesture.onKeyPressed(shift, 1_150)

        assertTrue(gesture.onKeyReleased(shift, 1_240))
    }

    @Test
    fun `Shift до отпускания Ctrl A не запускает перевод`() {
        val gesture = ShiftTapTranslateGesture(
            triggerKeyCode = shift,
            selectionModifierKeyCodes = setOf(control),
            selectAllKeyCode = selectAll
        )

        gesture.onKeyPressed(control, 1_000)
        gesture.onKeyPressed(selectAll, 1_020)
        gesture.onKeyReleased(selectAll, 1_040)
        gesture.onKeyPressed(shift, 1_050)

        assertFalse(gesture.onKeyReleased(shift, 1_100))
    }

    @Test
    fun `Ctrl Shift после Ctrl A не считается отдельным Shift`() {
        val gesture = ShiftTapTranslateGesture(
            triggerKeyCode = shift,
            selectionModifierKeyCodes = setOf(control),
            selectAllKeyCode = selectAll
        )

        gesture.onKeyPressed(control, 1_000)
        gesture.onKeyPressed(selectAll, 1_020)
        gesture.onKeyReleased(selectAll, 1_040)
        gesture.onKeyReleased(control, 1_060)
        gesture.onKeyPressed(control, 1_100)
        gesture.onKeyPressed(shift, 1_120)

        assertFalse(gesture.onKeyReleased(shift, 1_180))
    }

    @Test
    fun `два быстрых последовательных выделения оба обрабатываются`() {
        val gesture = ShiftTapTranslateGesture(shift)

        gesture.onSelectionCompleted(1_000)
        gesture.onKeyPressed(shift, 1_050)
        assertTrue(gesture.onKeyReleased(shift, 1_100))

        // Раньше глобальный cooldown 600 мс отбрасывал это второе независимое выделение.
        gesture.onSelectionCompleted(1_180)
        gesture.onKeyPressed(shift, 1_220)
        assertTrue(gesture.onKeyReleased(shift, 1_270))
    }

    @Test
    fun `настроенная modifier клавиша заменяет Shift`() {
        val alt = 56
        val gesture = ShiftTapTranslateGesture(triggerKeyCode = alt)

        gesture.onSelectionCompleted(1_000)
        gesture.onKeyPressed(shift, 1_050)
        assertFalse(gesture.onKeyReleased(shift, 1_100))

        gesture.onSelectionCompleted(1_200)
        gesture.onKeyPressed(alt, 1_250)
        assertTrue(gesture.onKeyReleased(alt, 1_320))
    }

    @Test
    fun `null отключает нативный tap жест для обычной комбинации`() {
        val gesture = ShiftTapTranslateGesture(triggerKeyCode = null)

        gesture.onSelectionCompleted(1_000)
        gesture.onKeyPressed(shift, 1_050)

        assertFalse(gesture.onKeyReleased(shift, 1_100))
    }
}
