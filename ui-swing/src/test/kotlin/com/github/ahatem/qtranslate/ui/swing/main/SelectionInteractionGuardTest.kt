package com.github.ahatem.qtranslate.ui.swing.main

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionInteractionGuardTest {
    @Test
    fun `клавиша или клик инвалидируют ожидающую вставку`() {
        val guard = SelectionInteractionGuard()
        val beforeInteraction = guard.snapshot()

        assertTrue(guard.isCurrent(beforeInteraction))
        guard.invalidate()
        assertFalse(guard.isCurrent(beforeInteraction))
        assertTrue(guard.isCurrent(guard.snapshot()))
    }
}
