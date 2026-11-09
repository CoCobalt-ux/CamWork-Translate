package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HotkeyScopePolicyTest {
    @Test
    fun `локальные действия из старой конфигурации не регистрируются глобально`() {
        val actions = listOf(HotkeyAction.FOCUS_INPUT, HotkeyAction.FOCUS_OUTPUT, HotkeyAction.FOCUS_EXTRA_OUTPUT)
        val config = Configuration.DEFAULT.copy(
            configVersion = ConfigMigrator.CURRENT_VERSION,
            hotkeys = actions.map { HotkeyBinding(it, KeyEvent.VK_F11, scope = HotkeyScope.GLOBAL) }
        )
        val normalized = ConfigMigrator.migrate(config, logger)
        normalized.hotkeys.forEach { assertEquals(HotkeyScope.LOCAL, it.scope) }
        assertEquals(normalized, ConfigMigrator.migrate(normalized, logger))
    }

    @Test
    fun `нормализация области сохраняет клавишу модификаторы и выключенное состояние`() {
        val binding = HotkeyBinding(
            HotkeyAction.FOCUS_INPUT, KeyEvent.VK_F11, modifiers = 128,
            isEnabled = false, scope = HotkeyScope.GLOBAL
        )
        val normalized = binding.normalizeScope()
        assertEquals(binding.copy(scope = HotkeyScope.LOCAL), normalized)
        assertFalse(normalized.isEnabled)
    }

    @Test
    fun `обычный перевод сохраняет выбранную пользователем область`() {
        val binding = HotkeyBinding(HotkeyAction.TRANSLATE, KeyEvent.VK_F11, scope = HotkeyScope.GLOBAL)
        assertEquals(binding, binding.normalizeScope())
        assertEquals(binding.copy(scope = HotkeyScope.LOCAL), binding.copy(scope = HotkeyScope.LOCAL).normalizeScope())
    }

    private companion object {
        val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
    }
}
