package com.github.ahatem.qtranslate.ui.swing.settings.panels

/** Ошибка модального окна не оставляет перевод выделения на вечной паузе. */
internal fun <T> withHotkeysSuspended(
    pause: (() -> Unit)?,
    resume: (() -> Unit)?,
    action: () -> T
): T {
    pause?.invoke()
    return try {
        action()
    } finally {
        resume?.invoke()
    }
}
