package com.github.ahatem.qtranslate.core.main.mvi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShiftTranslationFeedbackTest {

    @Test
    fun `обычная замена не показывает Shift подтверждение`() {
        assertFalse(MainEvent.PasteTranslation("Hello").showShiftFeedback)
    }

    @Test
    fun `Shift замена явно запрашивает подтверждение`() {
        val event = MainEvent.PasteTranslation(
            translatedText = "Hello",
            showShiftFeedback = true
        )

        assertTrue(event.showShiftFeedback)
    }
}
