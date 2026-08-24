package com.github.ahatem.qtranslate.ui.swing.main

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * Состояние разрешения «Универсальный доступ» на macOS.
 *
 * От него зависит почти весь перевод выделения: перехват одиночного Shift через JNativeHook,
 * отправка синтетического Cmd+C в чужое окно и чтение чужого интерфейса. Без разрешения ничего
 * из этого не работает, причём молча — события просто не приходят, а нажатия не доставляются.
 *
 * Выдать разрешение из программы нельзя: macOS требует именно ручного подтверждения, иначе любое
 * приложение могло бы читать всё, что печатает пользователь. Поэтому здесь только определение
 * состояния и открытие нужной страницы настроек — решение остаётся за пользователем.
 *
 * Важно для обновлений: разрешение привязано к подписи приложения. Пока сборки подписаны ad-hoc,
 * подпись меняется от версии к версии, и разрешение придётся выдавать заново. Это уходит вместе
 * с переходом на Developer ID.
 */
internal object MacAccessibilityPermission {

    private const val SETTINGS_URL =
        "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"

    val isMacOs: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    /**
     * @return `true`, если разрешение выдано либо система вообще не macOS — на других платформах
     *   спрашивать нечего и ограничивать поведение незачем.
     */
    fun isGranted(): Boolean {
        if (!isMacOs) return true
        return runCatching { ApplicationServices.INSTANCE.AXIsProcessTrusted() != ZERO }
            .getOrDefault(true)
    }

    /** Открывает страницу настроек прямо на нужном разделе, минуя поиск по системным настройкам. */
    fun openSettings(): Boolean =
        runCatching { ProcessBuilder("open", SETTINGS_URL).start() }.isSuccess

    private const val ZERO: Byte = 0

    /**
     * Возвращаемое значение объявлено как [Byte], а не [Boolean]: в CoreFoundation `Boolean` —
     * это один байт, тогда как JNA по умолчанию читает под Java-`boolean` целых четыре, и старшие
     * байты в регистре возврата не определены.
     */
    private interface ApplicationServices : Library {
        @Suppress("FunctionName")
        fun AXIsProcessTrusted(): Byte

        companion object {
            val INSTANCE: ApplicationServices by lazy {
                Native.load("ApplicationServices", ApplicationServices::class.java)
            }
        }
    }
}
