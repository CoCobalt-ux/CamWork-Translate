package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.testing.UiTestLogger
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TranslatorSelectorRecoveryTest {
    @Test
    fun `две тысячи обновлений загрузки сохраняют компоненты и доступность выбора`() {
        val directory = createTempDirectory("camwork-selector-").toFile()
        val logger = UiTestLogger()
        val loggerFactory = object : LoggerFactory {
            override fun getLogger(name: String): Logger = logger
        }
        val store = PluginKeyValueStore(directory)
        val manager = PluginManager(
            directory,
            SettingsRepository(directory, Json, logger),
            PluginFingerprintRepository(directory, Json),
            store, loggerFactory, NotificationBus()
        )
        val selected = mutableListOf<String>()
        SwingUtilities.invokeAndWait {
            val selector = TranslatorSelector(IconManager(manager), { _, id -> selected += id })
            val services = listOf(
                ServiceInfo("first", "Первый переводчик", null, ServiceRole.TRANSLATOR),
                ServiceInfo("second", "Второй переводчик", null, ServiceRole.TRANSLATOR)
            )
            val state = TranslatorSelectorState(services, "first", false)
            selector.render(state)
            val initialButtons = descendants(selector).filterIsInstance<JToggleButton>()
            assertEquals(2, initialButtons.size)
            repeat(2_000) { index ->
                selector.render(state.copy(isLoading = index % 2 == 0, selectedTranslatorId = if (index % 2 == 0) "second" else "first"))
                val current = descendants(selector).filterIsInstance<JToggleButton>()
                initialButtons.zip(current).forEach { (before, after) -> assertSame(before, after) }
            }
            selector.render(state.copy(selectedTranslatorId = "second"))
            assertTrue(initialButtons[1].isSelected)
            selector.render(state.copy(selectedTranslatorId = null))
            assertTrue(initialButtons.none { it.isSelected })
            selector.render(state.copy(selectedTranslatorId = "недоступный сервис"))
            assertTrue(initialButtons.none { it.isSelected })
            selector.render(state.copy(selectedTranslatorId = "second"))
            initialButtons[0].doClick(0)
            assertEquals(listOf("first"), selected)
            val productButtons = descendants(selector).filterIsInstance<AbstractButton>()
                .filter { !it.toolTipText.isNullOrBlank() }
            assertEquals(6, productButtons.size)
            productButtons.forEach { button ->
                assertTrue(button.isFocusable)
                assertTrue(!button.accessibleContext.accessibleName.isNullOrBlank())
            }
        }
        // DataStore не читались: сеть и файлы пользовательского профиля не используются.
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
