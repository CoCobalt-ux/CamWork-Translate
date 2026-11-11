package com.github.ahatem.qtranslate.ui.swing.main

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.io.File
import java.nio.file.Files

/** Автозапуск относится к текущему пользователю; решение Windows об отключении не переписывается. */
internal object SystemStartupRegistration : StartupRegistration by WindowsStartupRegistration(
    registry = WindowsStartupRegistry(),
    launcher = {
        resolveStartupLauncher(
            System.getProperty("jpackage.app-path"),
            ProcessHandle.current().info().command().orElse(null)
        )
    },
    isWindows = { System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true) }
)

internal enum class StartupFailure(val message: String) {
    UNSUPPORTED_OS("Эта настройка автозапуска доступна только в Windows."),
    NO_LAUNCHER("Для автозапуска запустите установленный или переносимый CamWork Translate.exe. Запуск через java.exe или javaw.exe не поддерживается."),
    REGISTRY_DENIED("Не удалось прочитать или изменить автозапуск Windows. Проверьте разрешения текущего пользователя."),
    DISABLED_BY_WINDOWS("Автозапуск CamWork Translate отключён в Windows. Включите его в «Параметры → Приложения → Автозагрузка» или в разделе «Автозагрузка приложений» Диспетчера задач."),
    DISABLED_BY_POLICY("Автозапуск отключён политикой Windows. Обратитесь к администратору компьютера."),
    UNKNOWN_WINDOWS_STATE("Не удалось подтвердить состояние автозапуска Windows. Проверьте CamWork Translate в «Параметры → Приложения → Автозагрузка»."),
    OTHER_LAUNCHER("В автозапуске Windows зарегистрирована другая копия CamWork Translate. Включите настройку явно, чтобы использовать текущую копию."),
    MISSING_ENTRY("Запись автозапуска отсутствует в Windows. Чтобы запускать CamWork Translate при входе, повторно включите настройку и нажмите «Применить»."),
    COMMAND_TOO_LONG("Путь к приложению слишком длинный для автозапуска Windows. Переместите приложение в каталог с более коротким путём."),
    VERIFICATION_FAILED("Windows не сохранила ожидаемую запись автозапуска. Изменение настройки не подтверждено."),
    ROLLBACK_FAILED("Не удалось восстановить прежнюю запись автозапуска Windows. Проверьте её в «Параметры → Приложения → Автозагрузка».")
}

internal data class StartupInspection(
    val enabled: Boolean = false,
    val registeredForThisLauncher: Boolean = false,
    val registeredElsewhere: Boolean = false,
    val failure: StartupFailure? = null,
    val launcherPath: String? = null
)

internal data class StartupCommand(val value: String, val expandable: Boolean = false)

internal data class StartupMutation(
    val previousCommand: StartupCommand?,
    val appliedCommand: StartupCommand?,
    val changed: Boolean,
    val launcherPath: String? = null
)

internal interface StartupRegistration {
    fun availabilityFailure(): StartupFailure?
    fun inspect(): StartupInspection
    fun change(enabled: Boolean, confirmedPreviousLauncher: String? = null): Result<StartupMutation, StartupFailure>
    fun rollback(mutation: StartupMutation): StartupFailure?
}

/** Узкая граница реестра позволяет проверять реальные операции без изменения пользовательского HKCU. */
internal interface StartupRegistry {
    fun readCommand(): StartupCommand?
    fun writeCommand(command: StartupCommand)
    fun deleteCommand()
    fun readApproval(): ByteArray?
    fun isDisabledByPolicy(): Boolean
}

internal class WindowsStartupRegistration(
    private val registry: StartupRegistry,
    private val launcher: () -> File?,
    private val isWindows: () -> Boolean
) : StartupRegistration {
    override fun availabilityFailure(): StartupFailure? = when {
        !isWindows() -> StartupFailure.UNSUPPORTED_OS
        runCatching { launcher() }.getOrNull() == null -> StartupFailure.NO_LAUNCHER
        else -> null
    }

    override fun inspect(): StartupInspection {
        availabilityFailure()?.let { return StartupInspection(failure = it) }
        return try {
            val currentLauncher = launcher() ?: return StartupInspection(failure = StartupFailure.NO_LAUNCHER)
            val command = registry.readCommand() ?: return StartupInspection()
            if (!belongsTo(command.value, currentLauncher)) {
                StartupInspection(registeredElsewhere = true, failure = StartupFailure.OTHER_LAUNCHER)
            } else {
                val failure = approvalFailure()
                StartupInspection(
                    enabled = failure == null,
                    registeredForThisLauncher = true,
                    failure = failure,
                    launcherPath = currentLauncher.canonicalPath.takeIf { failure == null }
                )
            }
        } catch (_: Exception) {
            StartupInspection(failure = StartupFailure.REGISTRY_DENIED)
        }
    }

    override fun change(
        enabled: Boolean,
        confirmedPreviousLauncher: String?
    ): Result<StartupMutation, StartupFailure> {
        availabilityFailure()?.let { failure ->
            // Отсутствующий нативный launcher не даёт права стирать запись установленной копии.
            return if (enabled) Err(failure) else Ok(StartupMutation(null, null, changed = false))
        }
        var mutation: StartupMutation? = null
        return try {
            val currentLauncher = launcher() ?: return Err(StartupFailure.NO_LAUNCHER)
            val previous = registry.readCommand()
            if (confirmedPreviousLauncher != null &&
                (previous == null || !isStaleLauncher(previous, currentLauncher, confirmedPreviousLauncher))
            ) {
                return Ok(StartupMutation(previous, previous, changed = false))
            }
            val desired = if (enabled) {
                approvalFailure()?.let { return Err(it) }
                StartupCommand("\"${currentLauncher.canonicalPath}\"").also {
                    // Ограничение Run относится ко всей командной строке, включая кавычки.
                    if (it.value.length > 260) return Err(StartupFailure.COMMAND_TOO_LONG)
                }
            } else {
                if (previous == null || !belongsTo(previous.value, currentLauncher)) {
                    return Ok(StartupMutation(previous, previous, changed = false))
                }
                null
            }
            val prepared = StartupMutation(
                previous, desired, previous != desired,
                launcherPath = currentLauncher.canonicalPath.takeIf { enabled }
            )
            mutation = prepared
            if (prepared.changed) {
                if (confirmedPreviousLauncher != null && registry.readCommand() != previous) {
                    return Err(StartupFailure.VERIFICATION_FAILED)
                }
                writeOrDelete(desired)
            }
            val failure = when {
                registry.readCommand() != desired -> StartupFailure.VERIFICATION_FAILED
                enabled -> approvalFailure()
                else -> null
            }
            if (failure != null) {
                Err(rollback(prepared) ?: failure)
            } else {
                Ok(prepared)
            }
        } catch (_: Exception) {
            Err(mutation?.let { rollback(it) } ?: StartupFailure.REGISTRY_DENIED)
        }
    }

    override fun rollback(mutation: StartupMutation): StartupFailure? {
        if (!mutation.changed) return null
        return try {
            val current = registry.readCommand()
            if (current == mutation.previousCommand) return null
            // Чужое изменение после нашей операции нельзя затереть откатом.
            if (current != mutation.appliedCommand) return StartupFailure.ROLLBACK_FAILED
            writeOrDelete(mutation.previousCommand)
            if (registry.readCommand() == mutation.previousCommand) null else StartupFailure.ROLLBACK_FAILED
        } catch (_: Exception) {
            StartupFailure.ROLLBACK_FAILED
        }
    }

    private fun writeOrDelete(command: StartupCommand?) {
        if (command == null) registry.deleteCommand() else registry.writeCommand(command)
    }

    private fun approvalFailure(): StartupFailure? {
        if (registry.isDisabledByPolicy()) return StartupFailure.DISABLED_BY_POLICY
        val approval = registry.readApproval() ?: return null
        // StartupApproved не имеет публичного API для обычного Win32. Читаем известные состояния
        // Windows 10/11, остальные считаем неподтверждёнными; бинарное значение никогда не меняем.
        if (approval.size < 12 || approval[1] != 0.toByte() ||
            approval[2] != 0.toByte() || approval[3] != 0.toByte()
        ) return StartupFailure.UNKNOWN_WINDOWS_STATE
        return when (approval[0].toInt() and 0xff) {
            0, 2, 6 -> null
            3, 7 -> StartupFailure.DISABLED_BY_WINDOWS
            else -> StartupFailure.UNKNOWN_WINDOWS_STATE
        }
    }

    private fun isStaleLauncher(command: StartupCommand, currentLauncher: File, confirmedPath: String): Boolean {
        val previousLauncher = executableIn(command.value) ?: return false
        return !command.expandable && previousLauncher.isAbsolute &&
            previousLauncher.name.equals(currentLauncher.name, ignoreCase = true) &&
            previousLauncher.canonicalPath.equals(confirmedPath, ignoreCase = true) &&
            !belongsTo(command.value, currentLauncher) && Files.notExists(previousLauncher.toPath())
    }

    private fun belongsTo(command: String, currentLauncher: File): Boolean =
        executableIn(command)?.canonicalPath?.equals(currentLauncher.canonicalPath, ignoreCase = true) == true

    private fun executableIn(command: String): File? {
        val trimmed = command.trim()
        val executable = if (trimmed.startsWith('"')) {
            val end = trimmed.indexOf('"', startIndex = 1)
            if (end <= 1) return null
            trimmed.substring(1, end)
        } else {
            // Старые записи без кавычек принимаются только целиком либо как простой путь без пробелов.
            if (File(trimmed).isFile) trimmed else trimmed.substringBefore(' ')
        }
        return executable.takeIf(String::isNotBlank)?.let(::File)
    }
}

/** jpackage задаёт точный путь launcher даже тогда, когда ProcessHandle сообщает javaw.exe. */
internal fun resolveStartupLauncher(jpackagePath: String?, processPath: String?): File? =
    sequenceOf(jpackagePath, processPath)
        .filterNotNull()
        .map(String::trim)
        .filter { it.isNotEmpty() && '"' !in it && '\n' !in it && '\r' !in it }
        .map(::File)
        .firstOrNull {
            it.isFile && it.extension.equals("exe", ignoreCase = true) &&
                !it.name.equals("java.exe", ignoreCase = true) &&
                !it.name.equals("javaw.exe", ignoreCase = true)
        }
        ?.canonicalFile

private class WindowsStartupRegistry : StartupRegistry {
    override fun readCommand(): StartupCommand? {
        if (!exists(RUN_KEY, VALUE_NAME)) return null
        val key = Advapi32Util.registryGetKey(WinReg.HKEY_CURRENT_USER, RUN_KEY, WinNT.KEY_QUERY_VALUE).value
        try {
            val type = IntByReference()
            val size = IntByReference()
            val result = Advapi32.INSTANCE.RegQueryValueEx(key, VALUE_NAME, 0, type, null as Pointer?, size)
            if (result == WinError.ERROR_FILE_NOT_FOUND) return null
            if (result != WinError.ERROR_SUCCESS && result != WinError.ERROR_MORE_DATA) throw Win32Exception(result)
            require(type.value == WinNT.REG_SZ || type.value == WinNT.REG_EXPAND_SZ)
            return StartupCommand(Advapi32Util.registryGetStringValue(key, VALUE_NAME), type.value == WinNT.REG_EXPAND_SZ)
        } finally {
            Advapi32Util.registryCloseKey(key)
        }
    }

    override fun writeCommand(command: StartupCommand) {
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, RUN_KEY)
        if (command.expandable) {
            Advapi32Util.registrySetExpandableStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, command.value)
        } else {
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, command.value)
        }
    }

    override fun deleteCommand() {
        if (exists(RUN_KEY, VALUE_NAME)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
        }
    }

    override fun readApproval(): ByteArray? = if (exists(APPROVED_KEY, VALUE_NAME)) {
        Advapi32Util.registryGetBinaryValue(WinReg.HKEY_CURRENT_USER, APPROVED_KEY, VALUE_NAME)
    } else null

    override fun isDisabledByPolicy(): Boolean =
        exists(POLICY_KEY, POLICY_NAME) &&
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, POLICY_KEY, POLICY_NAME) != 0

    private fun exists(key: String, name: String): Boolean =
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, name)

    private companion object {
        const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val APPROVED_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"
        const val POLICY_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\Explorer"
        const val POLICY_NAME = "DisableCurrentUserRun"
        const val VALUE_NAME = "CamWork Translate"
    }
}
