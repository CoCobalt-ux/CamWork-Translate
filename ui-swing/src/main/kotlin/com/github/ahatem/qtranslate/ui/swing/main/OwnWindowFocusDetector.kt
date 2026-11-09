package com.github.ahatem.qtranslate.ui.swing.main

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.ptr.IntByReference

/**
 * Определяет, принадлежит ли текущее активное окно самому CamWork Translate.
 *
 * Раньше мини-кнопку и авто-оверлей возле выделения решал `MainAppFrame.isActive` — состояние
 * активности JFrame. Это два разных вопроса: "выделение произошло в чужом окне" и "CamWork
 * сейчас в фокусе" независимы друг от друга. Пользователь мог держать окно CamWork рядом с
 * чатом (второй монитор, недавний клик по истории), и оно оставалось активным, хотя выделение
 * происходило во внешнем приложении, — тогда кнопка и оверлей не появлялись вовсе, хотя
 * поверхность была честно определена как чужая. Тот же паттерн PID-проверки уже используется в
 * LiveLensTextReader.isCamWorkWindow — здесь он применяется к переднему окну в момент выделения,
 * а не к окну под рамкой.
 */
internal fun isForegroundWindowOwnedByThisProcess(): Boolean {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        return false
    }
    return runCatching {
        val foreground = User32.INSTANCE.GetForegroundWindow()
        if (foreground.pointer.isNullPointer()) return@runCatching false
        val processId = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(foreground, processId)
        val pid = processId.value.toLong()
        if (pid == ProcessHandle.current().pid()) return@runCatching true

        ProcessHandle.of(pid)
            .map { process ->
                process.info().commandLine().orElse("").lowercase().let { commandLine ->
                    commandLine.contains("camwork translate") ||
                        commandLine.contains("camwork-translate") ||
                        commandLine.contains("app-all.jar")
                }
            }
            .orElse(false)
    }.getOrDefault(false)
}

private fun Pointer?.isNullPointer(): Boolean =
    this == null || Pointer.nativeValue(this) == 0L
