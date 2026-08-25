package com.github.ahatem.qtranslate.app

import java.io.BufferedReader

/** Читает встроенный в host JAR allowlist штатных plugin JAR конкретного релиза. */
internal object BundledPluginTrust {
    private const val RESOURCE = "META-INF/camwork/bundled-plugins.sha256"
    private val pluginIdPattern = Regex("[a-z0-9][a-z0-9._-]{1,127}")
    private val sha256Pattern = Regex("[a-f0-9]{64}")

    fun load(classLoader: ClassLoader = BundledPluginTrust::class.java.classLoader): Map<String, String> {
        val stream = requireNotNull(classLoader.getResourceAsStream(RESOURCE)) {
            "В сборке отсутствует обязательный allowlist штатных плагинов: $RESOURCE"
        }
        return stream.bufferedReader(Charsets.UTF_8).use(::parse).also { entries ->
            require(entries.isNotEmpty()) { "Allowlist штатных плагинов пуст: $RESOURCE" }
        }
    }

    internal fun parse(reader: BufferedReader): Map<String, String> = buildMap {
        reader.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed

            val separator = line.indexOf('=')
            require(separator > 0 && separator < line.lastIndex) {
                "Некорректная строка allowlist штатных плагинов: ${index + 1}"
            }
            val pluginId = line.substring(0, separator).trim()
            val hash = line.substring(separator + 1).trim().lowercase()
            require(pluginIdPattern.matches(pluginId) && sha256Pattern.matches(hash)) {
                "Некорректная запись allowlist штатных плагинов: ${index + 1}"
            }
            require(put(pluginId, hash) == null) {
                "Повторяющийся plugin id в allowlist: $pluginId"
            }
        }
    }
}
