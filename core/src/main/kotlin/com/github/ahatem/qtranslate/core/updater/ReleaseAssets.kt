package com.github.ahatem.qtranslate.core.updater

/**
 * Выбирает файл, который должен загрузить диалог обновления.
 *
 * В релизе лежат три варианта приложения и отдельные JAR плагинов. Поэтому брать первый файл
 * GitHub нельзя: по алфавиту им обычно оказывается один плагин. Сопоставление намеренно строгое;
 * неизвестный файл приводит пользователя на страницу релиза, где он сможет выбрать вручную.
 */
internal object ReleaseAssets {

    internal enum class Platform { WINDOWS, MACOS, OTHER }

    /**
     * Возвращает подходящий файл или `null`, если ни один вариант не распознан.
     * В Windows приоритет имеет пакет со встроенной Java, на остальных системах — переносимый ZIP.
     */
    fun selectDownload(assetNames: List<String>, onWindows: Boolean): String? {
        val platform = if (onWindows) Platform.WINDOWS else Platform.OTHER
        return selectDownload(assetNames, platform)
    }

    /**
     * Выбирает нативный пакет текущей ОС. На macOS архитектура обязательна: приложение с
     * несовместимым runtime хуже безопасного перехода на переносимый архив.
     */
    fun selectDownload(
        assetNames: List<String>,
        platform: Platform,
        architecture: String = ""
    ): String? {
        val preference = when (platform) {
            Platform.WINDOWS -> listOf(WINDOWS_PACKAGE, PORTABLE, APP_ONLY)
            Platform.MACOS -> when (architecture.lowercase()) {
                "aarch64", "arm64" -> listOf(MACOS_ARM64_DMG, MACOS_ARM64_APP, PORTABLE, APP_ONLY)
                "amd64", "x86_64", "x64" -> listOf(MACOS_X64_DMG, MACOS_X64_APP, PORTABLE, APP_ONLY)
                else -> listOf(PORTABLE, APP_ONLY)
            }
            Platform.OTHER -> listOf(PORTABLE, APP_ONLY)
        }
        return preference.firstNotNullOfOrNull { shape ->
            assetNames.firstOrNull { shape.matches(it) }
        }
    }

    private const val VERSION = "\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?"

    /** `CamWork-Translate-1.2.0-windows-x64.zip` — Windows-пакет со встроенной Java. */
    private val WINDOWS_PACKAGE = Regex(
        "CamWork-Translate-$VERSION-windows-x64\\.zip",
        RegexOption.IGNORE_CASE
    )

    private val MACOS_ARM64_DMG = macShape("arm64", "dmg")
    private val MACOS_ARM64_APP = macShape("arm64", "app\\.zip")
    private val MACOS_X64_DMG = macShape("x64", "dmg")
    private val MACOS_X64_APP = macShape("x64", "app\\.zip")

    private fun macShape(architecture: String, extension: String) = Regex(
        "CamWork-Translate-$VERSION-macos-$architecture\\.$extension",
        RegexOption.IGNORE_CASE
    )

    /**
     * `CamWork-Translate-1.0.0.zip` — переносимый архив.
     *
     * Отрицательная проверка ставится сразу после трёх чисел версии. Иначе разрешённый SemVer
     * prerelease жадно принимает `-windows-x64` за часть версии и отдаёт чужой runtime.
     */
    private val PORTABLE = Regex(
        "CamWork-Translate-\\d+\\.\\d+\\.\\d+(?!-(?:windows|macos)-)" +
            "(?:[-+][0-9A-Za-z.-]+)?\\.zip",
        RegexOption.IGNORE_CASE
    )

    /** `CamWork-Translate-App-1.0.0.jar` — приложение без плагинов. */
    private val APP_ONLY = Regex("CamWork-Translate-App-$VERSION\\.jar", RegexOption.IGNORE_CASE)
}
