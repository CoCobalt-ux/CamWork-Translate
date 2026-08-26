package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.Robot

/**
 * Отправляет синтетическую комбинацию и гарантированно отпускает обе клавиши.
 *
 * Robot не предоставляет атомарной операции shortcut. Если второй keyPress или waitForIdle
 * завершится ошибкой, обычная последовательность оставляет Ctrl/Cmd логически зажатым и ломает
 * следующие пользовательские Ctrl+C/Ctrl+V до нового физического нажатия модификатора.
 */
internal fun Robot.sendShortcutSafely(modifierKeyCode: Int, keyCode: Int) {
    var modifierAttempted = false
    var keyAttempted = false
    try {
        modifierAttempted = true
        keyPress(modifierKeyCode)
        keyAttempted = true
        keyPress(keyCode)
    } finally {
        if (keyAttempted) runCatching { keyRelease(keyCode) }
        if (modifierAttempted) runCatching { keyRelease(modifierKeyCode) }
    }
}

/** Тестируемое ядро той же гарантии без создания системного Robot. */
internal fun sendShortcutSafely(
    modifierKeyCode: Int,
    keyCode: Int,
    press: (Int) -> Unit,
    release: (Int) -> Unit
) {
    var modifierAttempted = false
    var keyAttempted = false
    try {
        modifierAttempted = true
        press(modifierKeyCode)
        keyAttempted = true
        press(keyCode)
    } finally {
        if (keyAttempted) runCatching { release(keyCode) }
        if (modifierAttempted) runCatching { release(modifierKeyCode) }
    }
}
