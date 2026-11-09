package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform

/** Проверяет нативный мост и ресурсы поддерживаемых пакетов до публикации. */
object NativeRuntimeCheck {
    fun verify(): String {
        val resources = listOf(
            "win32-x86-64/jnidispatch.dll",
            "linux-x86-64/libjnidispatch.so",
            "darwin-x86-64/libjnidispatch.jnilib",
            "darwin-aarch64/libjnidispatch.jnilib"
        )
        resources.forEach { resource ->
            checkNotNull(Native::class.java.getResource("/com/sun/jna/$resource")) {
                "В пакете отсутствует нативная библиотека JNA: $resource"
            }
        }
        Memory(java.lang.Long.BYTES.toLong()).use { memory ->
            memory.setLong(0L, 0x123456789ABCDEF0L)
            check(memory.getLong(0L) == 0x123456789ABCDEF0L) {
                "Нативная библиотека JNA некорректно работает с памятью"
            }
        }
        return "${Native.VERSION}:${Platform.RESOURCE_PREFIX}"
    }
}
