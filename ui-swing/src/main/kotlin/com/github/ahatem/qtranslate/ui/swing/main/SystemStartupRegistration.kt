package com.github.ahatem.qtranslate.ui.swing.main

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * Записывает приложение в автозапуск Windows для текущего пользователя.
 *
 * HKCU, а не HKLM: программа ставится без прав администратора, и автозапуск должен касаться
 * только того пользователя, который его включил. Имя значения совпадает с тем, что пишет
 * установщик, поэтому галка в настройках и пункт установщика управляют одной записью, а не
 * двумя конкурирующими.
 *
 * Сборка без собственного лаунчера (запуск из исходников или из jar) реестр не трогает вообще:
 * прописать ей в автозапуск нечего, а удаление могло бы стереть запись установленной копии.
 */
internal object SystemStartupRegistration {

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "CamWork Translate"

    /** Причина, по которой автозапуск нельзя применить. */
    enum class Failure {
        /** Не Windows — реестра нет. */
        UNSUPPORTED_OS,

        /** Процесс запущен не через собственный лаунчер: `java.exe` без аргументов ничего не откроет. */
        NO_LAUNCHER,

        /** Реестр отказал — нет доступа или политика запрещает запись. */
        REGISTRY_DENIED
    }

    /** @return `null`, если состояние автозапуска приведено к [enabled]. */
    fun apply(enabled: Boolean): Failure? {
        if (!isWindows()) return Failure.UNSUPPORTED_OS
        val launcher = launcherPath() ?: return Failure.NO_LAUNCHER
        return if (enabled) enable(launcher) else disable()
    }

    /**
     * `true`, если запись автозапуска указывает именно на этот лаунчер.
     *
     * Запись чужой копии (переносимая рядом с установленной) не считается своей: иначе одна копия
     * подхватывала бы и позже удаляла автозапуск другой.
     */
    fun isRegisteredForThisLauncher(): Boolean {
        if (!isWindows()) return false
        val launcher = launcherPath() ?: return false
        return runCatching {
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
                return@runCatching false
            }
            val registered = Advapi32Util
                .registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
                .trim()
                .removeSurrounding("\"")
            File(registered).canonicalFile == File(launcher).canonicalFile
        }.getOrDefault(false)
    }

    private fun enable(launcher: String): Failure? = runCatching {
        Advapi32Util.registrySetStringValue(
            WinReg.HKEY_CURRENT_USER,
            RUN_KEY,
            VALUE_NAME,
            "\"$launcher\""
        )
    }.fold(onSuccess = { null }, onFailure = { Failure.REGISTRY_DENIED })

    private fun disable(): Failure? = runCatching {
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
        }
    }.fold(onSuccess = { null }, onFailure = { Failure.REGISTRY_DENIED })

    /**
     * Путь к нативному лаунчеру приложения. `java.exe` отбрасывается: у сборки из исходников
     * своего лаунчера нет, и в автозапуск ей попадать нечем.
     */
    private fun launcherPath(): String? {
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        val file = File(command)
        if (!file.isFile) return null
        val name = file.name.lowercase()
        if (name == "java.exe" || name == "javaw.exe") return null
        return file.absolutePath
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}
