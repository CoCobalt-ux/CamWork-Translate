package com.github.ahatem.qtranslate.core.main.mvi

import kotlin.test.Test
import kotlin.test.assertEquals

class ShiftTranslationFeedbackTest {

    @Test
    fun `обычная замена по умолчанию проходит без уведомлений`() {
        assertEquals(PasteFeedback.NONE, MainEvent.PasteTranslation("Hello").feedback)
    }
}
