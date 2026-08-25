package com.github.ahatem.qtranslate.core.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundledPluginTrustPolicyTest {
    private val trustedHash = "b".repeat(64)
    private val policy = BundledPluginTrustPolicy(mapOf("google-services" to trustedHash.uppercase()))

    @Test
    fun `первый запуск не требует подтверждения`() {
        assertFalse(policy.requiresUserVerification("google-services", null, trustedHash))
    }

    @Test
    fun `подмена штатного jar блокируется уже на первом запуске`() {
        assertTrue(policy.requiresUserVerification("google-services", null, "c".repeat(64)))
    }

    @Test
    fun `явно установленный сторонний плагин разрешён на первом запуске`() {
        assertFalse(policy.requiresUserVerification("third-party", null, "c".repeat(64)))
    }

    @Test
    fun `неизменившийся сторонний плагин остаётся доверенным`() {
        val hash = "a".repeat(64)
        assertFalse(policy.requiresUserVerification("third-party", hash, hash.uppercase()))
    }

    @Test
    fun `штатное обновление из host allowlist принимается автоматически`() {
        assertFalse(policy.requiresUserVerification("google-services", "a".repeat(64), trustedHash))
        assertTrue(policy.isTrustedBundledUpdate("google-services", "a".repeat(64), trustedHash))
    }

    @Test
    fun `подменённый штатный jar требует подтверждения`() {
        assertTrue(policy.requiresUserVerification("google-services", trustedHash, "c".repeat(64)))
    }

    @Test
    fun `сохранённый fingerprint не обходит allowlist текущего релиза`() {
        val staleOrModifiedHash = "c".repeat(64)

        assertTrue(
            policy.requiresUserVerification(
                pluginId = "google-services",
                savedHash = staleOrModifiedHash,
                currentHash = staleOrModifiedHash
            )
        )
        assertFalse(
            policy.isTrustedBundledUpdate(
                pluginId = "google-services",
                savedHash = staleOrModifiedHash,
                currentHash = staleOrModifiedHash
            )
        )
    }

    @Test
    fun `неизвестное обновление стороннего плагина требует подтверждения`() {
        assertTrue(policy.requiresUserVerification("third-party", "a".repeat(64), "c".repeat(64)))
    }
}
