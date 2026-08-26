package com.github.ahatem.qtranslate.ui.swing.main

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemClipboardGenerationTest {
    @Test
    fun `внешнее копирование того же текста запрещает restore в Windows`() {
        assertFalse(
            ownsTemporaryClipboard(
                expectedText = "Hello",
                currentText = "Hello",
                expectedGeneration = 41,
                currentGeneration = 42
            )
        )
    }

    @Test
    fun `на неизменённом clipboard restore разрешён`() {
        assertTrue(
            ownsTemporaryClipboard(
                expectedText = "Hello",
                currentText = "Hello",
                expectedGeneration = 42,
                currentGeneration = 42
            )
        )
    }

    @Test
    fun `на других системах остаётся безопасная проверка текста`() {
        assertTrue(
            ownsTemporaryClipboard(
                expectedText = "Hello",
                currentText = "Hello",
                expectedGeneration = null,
                currentGeneration = null
            )
        )
        assertFalse(
            ownsTemporaryClipboard(
                expectedText = "Hello",
                currentText = "Привет",
                expectedGeneration = null,
                currentGeneration = null
            )
        )
    }
}
