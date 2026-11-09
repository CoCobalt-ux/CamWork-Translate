package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.ui.swing.testing.UiTestLogger
import javax.swing.SwingUtilities
import javax.swing.JLabel
import javax.swing.JList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageComboBoxRecoveryTest {
    @Test
    fun `обновление сервисов и фильтра не подменяет выбранный язык`() = withCombo { combo, selected, _ ->
        SwingUtilities.invokeAndWait {
            repeat(500) { index ->
                combo.render(
                    if (index % 2 == 0) listOf(LanguageCode.ENGLISH) else emptyList(),
                    LanguageCode("de"), null, true
                )
                assertEquals(LanguageCode("de"), combo.selectedItem)
            }
            assertEquals(emptyList(), selected)
            combo.render(listOf(LanguageCode.ENGLISH), LanguageCode("de"), null, true)
            combo.selectedItem = LanguageCode.ENGLISH
            assertEquals(listOf(LanguageCode.ENGLISH), selected)
        }
    }

    @Test
    fun `пустой список во время загрузки не показывает другой язык`() = withCombo { combo, selected, _ ->
        SwingUtilities.invokeAndWait {
            combo.render(emptyList(), LanguageCode.RUSSIAN, null, false)
            assertEquals(LanguageCode.RUSSIAN, combo.selectedItem)
            assertFalse(combo.isEnabled)
            assertEquals(emptyList(), selected)
        }
    }

    @Test
    fun `отложенный старый рендер не отменяет свежий выбор на EDT`() = withCombo { combo, selected, _ ->
        SwingUtilities.invokeAndWait {
            val background = Thread {
                combo.render(listOf(LanguageCode.RUSSIAN), LanguageCode.RUSSIAN, null, true)
            }
            background.start()
            background.join(2_000)
            assertFalse(background.isAlive)
            combo.render(listOf(LanguageCode("de")), LanguageCode("de"), null, true)
        }
        SwingUtilities.invokeAndWait {
            assertEquals(LanguageCode("de"), combo.selectedItem)
            assertEquals(emptyList(), selected)
        }
    }

    @Test
    fun `компактный список показывает AUTO а определённый язык только в подсказке`() =
        withCombo(compact = true) { combo, selected, localizer ->
            SwingUtilities.invokeAndWait {
                listOf(LanguageCode.ENGLISH, LanguageCode.GERMAN, LanguageCode.RUSSIAN).forEach { detected ->
                    combo.render(listOf(LanguageCode.AUTO, detected), LanguageCode.AUTO, detected, true)
                    val label = combo.renderer.getListCellRendererComponent(JList<LanguageCode>(), combo.selectedItem as LanguageCode, -1, false, false) as JLabel
                    assertEquals("AUTO", label.text)
                    assertEquals(LanguageCode.AUTO, combo.selectedItem)
                    assertTrue(label.toolTipText.startsWith(localizer.getString("common.auto_detect")))
                    assertTrue(label.toolTipText.contains(detected.getDisplayName()))
                }
                assertTrue(selected.isEmpty())
            }
        }

    @Test
    fun `полный список ставит режим AUTO перед определённым языком`() = withCombo { combo, selected, localizer ->
        SwingUtilities.invokeAndWait {
            combo.render(listOf(LanguageCode.AUTO, LanguageCode.ENGLISH), LanguageCode.AUTO, LanguageCode.ENGLISH, true)
            val label = combo.renderer.getListCellRendererComponent(JList<LanguageCode>(), combo.selectedItem as LanguageCode, -1, false, false) as JLabel
            assertEquals("${localizer.getString("common.auto_detect")} (${LanguageCode.ENGLISH.getDisplayName()})", label.text)
            assertEquals(LanguageCode.AUTO, combo.selectedItem)
            assertTrue(selected.isEmpty())
        }
    }

    private fun withCombo(
        compact: Boolean = false,
        action: (LanguageComboBox, MutableList<LanguageCode>, LocalizationManager) -> Unit
    ) {
        val directory = createTempDirectory("camwork-language-recovery-").toFile()
        try {
            val selected = mutableListOf<LanguageCode>()
            val localizer = LocalizationManager(directory, LanguageTomlParser(), UiTestLogger())
            lateinit var combo: LanguageComboBox
            SwingUtilities.invokeAndWait { combo = LanguageComboBox({ selected += it }, localizer, compact = compact) }
            action(combo, selected, localizer)
        } finally {
            directory.deleteRecursively()
        }
    }
}
