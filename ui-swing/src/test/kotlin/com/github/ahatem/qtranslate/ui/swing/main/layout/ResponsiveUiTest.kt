package com.github.ahatem.qtranslate.ui.swing.main.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponsiveUiTest {

    @Test
    fun `ширина 480 использует компактную панель, а 900 — обычную`() {
        assertTrue(ResponsiveUi.shouldUseCompactToolbar(480))
        assertFalse(ResponsiveUi.shouldUseCompactToolbar(900))
    }

    @Test
    fun `длинный текст сокращается до доступной ширины`() {
        val result = ResponsiveUi.elideText("Очень длинное название переводчика", 12, String::length)

        assertTrue(result.endsWith("…"))
        assertTrue(result.length <= 12)
    }

    @Test
    fun `сокращение не разрывает Unicode символ`() {
        val result = ResponsiveUi.elideText("A😀BC", 3) { it.codePointCount(0, it.length) }

        assertEquals("A😀…", result)
    }

    @Test
    fun `нулевая ширина не возвращает строку поверх соседних элементов`() {
        assertEquals("", ResponsiveUi.elideText("Google Translate", 0, String::length))
    }
}
