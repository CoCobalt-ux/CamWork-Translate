package com.github.ahatem.qtranslate.app

import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BundledPluginTrustTest {
    @Test
    fun `сгенерированный allowlist содержит основную цепочку перевода`() {
        val entries = BundledPluginTrust.load()

        assertTrue(entries.keys.containsAll(setOf("google-services", "bing-services", "deepl-services")))
    }

    @Test
    fun `валидный allowlist разбирается без изменения hash`() {
        val hash = "a".repeat(64)
        val parsed = BundledPluginTrust.parse(BufferedReader(StringReader("# release\ngoogle-services=$hash\n")))

        assertEquals(mapOf("google-services" to hash), parsed)
    }

    @Test
    fun `повторяющийся plugin id отклоняется`() {
        val hash = "a".repeat(64)
        assertFailsWith<IllegalArgumentException> {
            BundledPluginTrust.parse(
                BufferedReader(StringReader("google-services=$hash\ngoogle-services=$hash\n"))
            )
        }
    }

    @Test
    fun `некорректный hash отклоняется`() {
        assertFailsWith<IllegalArgumentException> {
            BundledPluginTrust.parse(BufferedReader(StringReader("google-services=broken\n")))
        }
    }
}
