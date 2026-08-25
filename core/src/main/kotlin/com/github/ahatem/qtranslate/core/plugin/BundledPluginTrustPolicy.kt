package com.github.ahatem.qtranslate.core.plugin

/** Отличает штатное обновление CamWork от неизвестной подмены plugin JAR. */
internal class BundledPluginTrustPolicy(
    trustedHashes: Map<String, String>
) {
    private val trustedHashes = trustedHashes.mapValues { (_, hash) -> hash.lowercase() }

    fun requiresUserVerification(pluginId: String, savedHash: String?, currentHash: String): Boolean {
        // Для штатного ID manifest текущего host JAR всегда является источником истины.
        // Сохранённый fingerprint может принадлежать предыдущему релизу или уже однажды
        // подтверждённой подмене, поэтому он не должен обходить release allowlist.
        trustedHashes[pluginId]?.let { bundledHash ->
            return !bundledHash.equals(currentHash, ignoreCase = true)
        }

        return savedHash != null && !savedHash.equals(currentHash, ignoreCase = true)
    }

    fun isTrustedBundledUpdate(pluginId: String, savedHash: String?, currentHash: String): Boolean {
        val bundledHash = trustedHashes[pluginId] ?: return false
        return savedHash != null &&
            !savedHash.equals(currentHash, ignoreCase = true) &&
            bundledHash.equals(currentHash, ignoreCase = true)
    }
}
