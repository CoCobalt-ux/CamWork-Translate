package com.github.ahatem.qtranslate.ui.swing.shared.theme

import com.formdev.flatlaf.IntelliJTheme
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CamWorkThemeTest {

    @Test
    fun `встроенные темы CamWork загружаются в правильном режиме`() {
        assertTheme("themes/qtranslate-light.theme.json", "CamWork Light", false)
        assertTheme("themes/qtranslate-dark.theme.json", "CamWork Dark", true)
    }

    @Test
    fun `темы CamWork используются по умолчанию`() {
        assertEquals("custom:camwork_light", ThemeManager.platformDefaultLightThemeId())
        assertEquals("custom:camwork_dark", ThemeManager.platformDefaultDarkThemeId())
    }

    @Test
    fun `missing custom theme resource fails instead of reporting success`() {
        val theme = createCustomTheme("missing", "Missing", false, "themes/missing.theme.json")
        assertFailsWith<IllegalArgumentException> { theme.apply() }
    }

    @Test
    fun `theme text and primary actions meet WCAG contrast targets`() {
        // Светлая тема: тёмный текст и контрастный бирюзовый для действий.
        assertContrast("#10262B", "#F3F7F7", 7.0)
        assertContrast("#10262B", "#FFFFFF", 7.0)
        assertContrast("#425B60", "#F3F7F7", 4.5)
        assertContrast("#687D81", "#F3F7F7", 3.0)
        assertContrast("#061B18", "#00A88F", 4.5)
        assertContrast("#007361", "#F3F7F7", 4.5)
        assertContrast("#10262B", "#C9F3E8", 7.0)

        // Тёмная тема: фирменный неоновый бирюзовый на глубоком синем фоне.
        assertContrast("#E7F5F2", "#0B1118", 7.0)
        assertContrast("#E7F5F2", "#121D27", 7.0)
        assertContrast("#A2B6B2", "#0B1118", 4.5)
        assertContrast("#778C88", "#0B1118", 3.0)
        assertContrast("#071A17", "#64FFDA", 4.5)
        assertContrast("#64FFDA", "#0B1118", 4.5)
        assertContrast("#7CFFE1", "#0B1118", 4.5)
        assertContrast("#E7F5F2", "#174B40", 4.5)
    }

    /**
     * Secondary text is drawn on raised surfaces too — menu accelerators, table headers,
     * popups — where there is less contrast available than on the canvas. Checking only
     * against the canvas hides failures on exactly the surfaces where muted text is
     * hardest to read.
     */
    @Test
    fun `muted and disabled text stay readable on every surface`() {
        listOf("#0B1118", "#121D27", "#192733").forEach { surface ->
            assertContrast("#A2B6B2", surface, 4.5)
            assertContrast("#778C88", surface, 3.0)
            assertContrast("#E7F5F2", surface, 7.0)
        }
        listOf("#F3F7F7", "#FFFFFF", "#E5EEEE").forEach { surface ->
            assertContrast("#425B60", surface, 4.5)
            assertContrast("#687D81", surface, 3.0)
            assertContrast("#10262B", surface, 7.0)
        }
    }

    @Test
    fun `status colours are readable on both canvases`() {
        listOf("#197346", "#8A5800", "#B42318").forEach {
            assertContrast(it, "#F3F7F7", 4.5)
        }
        listOf("#73E2A7", "#FDBA74", "#FF8A80").forEach {
            assertContrast(it, "#0B1118", 4.5)
        }
    }

    private fun assertTheme(resource: String, expectedName: String, expectedDark: Boolean) {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(resource))
        stream.use {
            val laf = IntelliJTheme.createLaf(it)
            assertEquals(expectedName, laf.name)
            assertEquals(expectedDark, laf.isDark)
        }
    }

    private fun assertContrast(foreground: String, background: String, minimum: Double) {
        val ratio = contrastRatio(Color.decode(foreground), Color.decode(background))
        assertTrue(ratio >= minimum, "$foreground on $background has contrast $ratio; expected at least $minimum")
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val lighter = maxOf(relativeLuminance(first), relativeLuminance(second))
        val darker = minOf(relativeLuminance(first), relativeLuminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
