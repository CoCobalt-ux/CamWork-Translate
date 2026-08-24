package com.github.ahatem.qtranslate.ui.swing.main

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemScreenCaptureGuardTest {
    private val meta = 1
    private val shift = 2
    private val s = 3
    private val printScreen = 4
    private val escape = 5

    @Test
    fun `Win Shift S блокирует выделение области и последующий click`() {
        val guard = createGuard()

        assertFalse(guard.onKeyPressed(meta, 100))
        assertFalse(guard.onKeyPressed(shift, 110))
        assertTrue(guard.onKeyPressed(s, 120))
        assertTrue(guard.onPointerPressed(300))
        assertTrue(guard.onPointerDragged(350))
        assertTrue(guard.onPointerReleased(400))
        assertTrue(guard.isSuppressed(499))
        assertFalse(guard.isSuppressed(501))
    }

    @Test
    fun `обычный Shift и выделение текста не блокируются`() {
        val guard = createGuard()

        assertFalse(guard.onKeyPressed(shift, 100))
        assertFalse(guard.onKeyReleased(shift, 150))
        assertFalse(guard.onPointerPressed(200))
        assertFalse(guard.onPointerDragged(220))
        assertFalse(guard.onPointerReleased(240))
    }

    @Test
    fun `Print Screen кратко защищает clipboard без мыши`() {
        val guard = createGuard()

        assertTrue(guard.onKeyPressed(printScreen, 100))
        assertTrue(guard.isSuppressed(299))
        assertFalse(guard.isSuppressed(301))
    }

    @Test
    fun `Print Screen поддерживает отложенный выбор области мышью`() {
        val guard = createGuard(printScreenTimeoutMs = 1_000)

        assertTrue(guard.onKeyPressed(printScreen, 100))
        assertTrue(guard.onPointerPressed(700))
        assertTrue(guard.onPointerDragged(750))
        assertTrue(guard.onPointerReleased(800))
        assertTrue(guard.isSuppressed(899))
        assertFalse(guard.isSuppressed(901))
    }

    @Test
    fun `Print Screen без drag завершается первым pointer release`() {
        val guard = createGuard(printScreenTimeoutMs = 1_000)

        guard.onKeyPressed(printScreen, 100)
        assertTrue(guard.onPointerPressed(400))
        assertTrue(guard.onPointerReleased(410))

        assertTrue(guard.isSuppressed(509))
        assertFalse(guard.isSuppressed(511))
    }

    @Test
    fun `первый pointer продлевает короткий Print Screen до завершения области`() {
        val guard = createGuard(printScreenTimeoutMs = 200)

        guard.onKeyPressed(printScreen, 100)
        assertTrue(guard.onPointerPressed(250))
        assertTrue(guard.isSuppressed(1_000))
        assertTrue(guard.onPointerReleased(1_100))
        assertFalse(guard.isSuppressed(1_201))
    }

    @Test
    fun `Escape завершает режим выбора области с коротким запасом`() {
        val guard = createGuard()

        guard.onKeyPressed(meta, 100)
        guard.onKeyPressed(shift, 110)
        guard.onKeyPressed(s, 120)

        assertTrue(guard.onKeyPressed(escape, 200))
        assertTrue(guard.isSuppressed(299))
        assertFalse(guard.isSuppressed(301))
    }

    @Test
    fun `screen capture запрещает восстановление временного paste clipboard`() {
        assertFalse(
            shouldRestorePasteClipboard(
                ownsTranslationClipboard = true,
                isScreenCaptureSuppressed = true
            )
        )
        assertTrue(
            shouldRestorePasteClipboard(
                ownsTranslationClipboard = true,
                isScreenCaptureSuppressed = false
            )
        )
    }

    private fun createGuard(printScreenTimeoutMs: Long = 200) = SystemScreenCaptureGuard(
        metaKeyCode = meta,
        shiftKeyCode = shift,
        snippingKeyCode = s,
        printScreenKeyCode = printScreen,
        escapeKeyCode = escape,
        regionTimeoutMs = 1_000,
        printScreenTimeoutMs = printScreenTimeoutMs,
        cooldownMs = 100
    )
}
