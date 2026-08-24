package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import kotlin.test.Test
import kotlin.test.assertEquals

class TranslatorSelectorRolesTest {

    @Test
    fun `основной селектор содержит только переводчик`() {
        assertEquals(setOf(ServiceRole.TRANSLATOR), MAIN_SELECTOR_ROLES)
    }
}
