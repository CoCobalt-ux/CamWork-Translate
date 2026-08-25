package com.github.ahatem.qtranslate.core.updater

import java.io.File
import java.util.Locale

/** Снимок окружения, влияющего только на выбор релизного артефакта. */
internal data class UpdateRuntimeInfo(
    val platform: ReleaseAssets.Platform,
    val architecture: String,
    val windowsDistribution: ReleaseAssets.WindowsDistribution
) {
    companion object {
        fun current(): UpdateRuntimeInfo {
            val osName = System.getProperty("os.name").orEmpty()
            val platform = when {
                osName.startsWith("Windows", ignoreCase = true) -> ReleaseAssets.Platform.WINDOWS
                osName.startsWith("Mac", ignoreCase = true) -> ReleaseAssets.Platform.MACOS
                else -> ReleaseAssets.Platform.OTHER
            }
            return UpdateRuntimeInfo(
                platform = platform,
                architecture = System.getProperty("os.arch").orEmpty(),
                windowsDistribution = if (platform == ReleaseAssets.Platform.WINDOWS) {
                    WindowsDistributionDetector.detect(System.getProperty("jpackage.app-path"))
                } else {
                    ReleaseAssets.WindowsDistribution.PORTABLE
                }
            )
        }
    }
}

/**
 * Отличает установленный Inno Setup app-image от распакованного Windows ZIP без реестра и записи.
 *
 * Одного `uninsNNN.exe` недостаточно: файл с таким именем может случайно оказаться в portable
 * папке. Inno Setup создаёт рядом одноимённый `.dat`; только существующая пара считается
 * подтверждением установки. Любая ошибка, недоступный каталог или неизвестный launcher безопасно
 * трактуются как portable.
 */
internal object WindowsDistributionDetector {
    private val UNINSTALLER_EXE = Regex("unins(\\d+)\\.exe", RegexOption.IGNORE_CASE)

    fun detect(launcherPath: String?): ReleaseAssets.WindowsDistribution {
        val launcher = launcherPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { file -> runCatching { file.isFile }.getOrDefault(false) }
            ?: return ReleaseAssets.WindowsDistribution.PORTABLE
        val directory = launcher.absoluteFile.parentFile
            ?: return ReleaseAssets.WindowsDistribution.PORTABLE
        val fileNames = runCatching {
            directory.listFiles()
                ?.asSequence()
                ?.filter { it.isFile }
                ?.map { it.name.lowercase(Locale.ROOT) }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())

        val hasCompleteUninstaller = fileNames.any { name ->
            val match = UNINSTALLER_EXE.matchEntire(name) ?: return@any false
            "unins${match.groupValues[1]}.dat" in fileNames
        }
        return if (hasCompleteUninstaller) {
            ReleaseAssets.WindowsDistribution.INSTALLED
        } else {
            ReleaseAssets.WindowsDistribution.PORTABLE
        }
    }
}
