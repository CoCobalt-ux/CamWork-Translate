package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.ui.swing.testing.UiTestLogger
import javax.swing.SwingUtilities
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LanguageComboBoxRecoveryTest {
    @Test
    fun `обновление сервисов и фильтра не подменяет выбранный язык`() = withCombo { combo, selected ->
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
    fun `пустой список во время загрузки не показывает другой язык`() = withCombo { combo, selected ->
        SwingUtilities.invokeAndWait {
            combo.render(emptyList(), LanguageCode.RUSSIAN, null, false)
            assertEquals(LanguageCode.RUSSIAN, combo.selectedItem)
            assertFalse(combo.isEnabled)
            assertEquals(emptyList(), selected)
        }
    }

    @Test
    fun `отложенный старый рендер не отменяет свежий выбор на EDT`() = withCombo { combo, selected ->
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

    private fun withCombo(action: (LanguageComboBox, MutableList<LanguageCode>) -> Unit) {
        val directory = createTempDirectory("camwork-language-recovery-").toFile()
        try {
            val selected = mutableListOf<LanguageCode>()
            val localizer = LocalizationManager(directory, LanguageTomlParser(), UiTestLogger())
            lateinit var combo: LanguageComboBox
            SwingUtilities.invokeAndWait { combo = LanguageComboBox({ selected += it }, localizer) }
            action(combo, selected)
        } finally {
            directory.deleteRecursively()
        }
    }
}
