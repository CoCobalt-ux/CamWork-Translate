package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyntheticShortcutTest {
    @Test
    fun `обе клавиши отпускаются в обратном порядке`() {
        val events = mutableListOf<String>()

        sendShortcutSafely(
            modifierKeyCode = KeyEvent.VK_CONTROL,
            keyCode = KeyEvent.VK_C,
            press = { events += "press:$it" },
            release = { events += "release:$it" }
        )

        assertEquals(
            listOf(
                "press:${KeyEvent.VK_CONTROL}",
                "press:${KeyEvent.VK_C}",
                "release:${KeyEvent.VK_C}",
                "release:${KeyEvent.VK_CONTROL}"
            ),
            events
        )
    }

    @Test
    fun `Ctrl отпускается если нажатие основной клавиши завершилось ошибкой`() {
        val released = mutableListOf<Int>()

        assertFailsWith<IllegalStateException> {
            sendShortcutSafely(
                modifierKeyCode = KeyEvent.VK_CONTROL,
                keyCode = KeyEvent.VK_V,
                press = { key ->
                    if (key == KeyEvent.VK_V) error("Сбой синтетического ввода")
                },
                release = { released += it }
            )
        }

        assertEquals(listOf(KeyEvent.VK_V, KeyEvent.VK_CONTROL), released)
    }
}
