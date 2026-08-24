package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.core.shared.AppConstants
import java.io.File

/**
 * Определяет каталог постоянных данных CamWork Translate:
 * настроек, плагинов, истории, тем и журналов.
 *
 * Сначала используется каталог рядом с JAR или нативным запускным файлом. Благодаря этому
 * приложение остаётся переносимым и его можно копировать вместе со всеми настройками.
 *
 * ```
 * CamWork Translate/
 *   ├── CamWork Translate.exe
 *   ├── plugins/
 *   ├── themes/
 *   ├── datastore/
 *   └── plugins_data/
 * ```
 *
 * Если каталог приложения недоступен для записи, используется стандартный системный путь:
 * `%APPDATA%\CamWork Translate` в Windows, `~/Library/Application Support/CamWork Translate`
 * в macOS или `$XDG_CONFIG_HOME/CamWork Translate` в Linux.
 */
object AppDataDirectory {

    /** Возвращает каталог данных и создаёт его, если он отсутствует. */
    fun resolve(): File {
        System.getProperty("appData")?.let {
            val file = File(it)
            return file.also { f -> f.mkdirs() }
        }

        nativeLauncherDirectory()?.let { directory ->
            if (directory.canWrite()) return directory.also { it.mkdirs() }
        }

        val jarDir = jarLocation()
        if (jarDir != null && jarDir.canWrite()) {
            return jarDir.also { it.mkdirs() }
        }
        return osFallback().also { it.mkdirs() }
    }

    /** Возвращает каталог запущенного JAR или `null`, когда определить его невозможно. */
    private fun jarLocation(): File? = runCatching {
        val uri = AppDataDirectory::class.java
            .protectionDomain
            .codeSource
            .location
            .toURI()
        File(uri).parentFile
    }.getOrNull()

    /** Возвращает каталог нативного запускающего файла, созданного jpackage. */
    private fun nativeLauncherDirectory(): File? =
        System.getProperty("jpackage.app-path")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.parentFile

    private fun osFallback(): File {
        val base = when {
            os().contains("win") ->
                System.getenv("APPDATA")
                    ?: (System.getProperty("user.home") + "\\AppData\\Roaming")
            os().contains("mac") ->
                System.getProperty("user.home") + "/Library/Application Support"
            else ->
                System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                    ?: (System.getProperty("user.home") + "/.config")
        }
        return File(base, AppConstants.DATA_DIRECTORY_NAME)
    }

    private fun os(): String =
        System.getProperty("os.name").orEmpty().lowercase()
}
