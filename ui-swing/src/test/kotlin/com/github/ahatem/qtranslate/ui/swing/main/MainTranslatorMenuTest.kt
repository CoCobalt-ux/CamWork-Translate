package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class MainTranslatorMenuTest {
    @Test
    fun `AI Translate скрыт только из списка основных переводчиков`() {
        val google = ServiceInfo("google-plugin:default:google", "Google", null, ServiceRole.TRANSLATOR)
        val ai = ServiceInfo(MAIN_MENU_AI_TRANSLATOR_ID, "AI Translate", null, ServiceRole.TRANSLATOR)

        assertEquals(listOf(google), listOf(google, ai).forMainTranslatorMenu())
    }

    @Test
    fun `скрытый AI заменяется основным Google независимо от порядка загрузки`() {
        val deepl = ServiceInfo(
            "deepl-services:default:deepl-services-translator",
            "DeepL",
            null,
            ServiceRole.TRANSLATOR
        )
        val google = ServiceInfo(
            "google-services:default:google-translator",
            "Google",
            null,
            ServiceRole.TRANSLATOR
        )

        assertEquals(google.id, listOf(deepl, google).preferredMainTranslatorId())
    }
}
