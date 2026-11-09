package com.github.ahatem.qtranslate.ui.swing.testing

import com.github.ahatem.qtranslate.api.core.Logger
import java.util.concurrent.CopyOnWriteArrayList

internal class UiTestLogger : Logger {
    val errors = CopyOnWriteArrayList<String>()
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, error: Throwable?) { errors += message }
}
