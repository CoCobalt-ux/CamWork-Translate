package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.sun.jna.Native
import com.sun.jna.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeRuntimeCheckTest {
    @Test
    fun `нативный мост загружается и пакет содержит ресурсы всех целевых архитектур`() {
        assertEquals("${Native.VERSION}:${Platform.RESOURCE_PREFIX}", NativeRuntimeCheck.verify())
    }
}
