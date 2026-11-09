package com.github.ahatem.qtranslate.core.shared.logging

import com.github.ahatem.qtranslate.api.core.Logger

/** Записывает место сбоя без текста исключения, переводов и ключей провайдера. */
fun Logger.recordFailure(component: String, failure: Throwable) {
    val location = failure.stackTrace.take(8).joinToString(" <- ") {
        "${it.className}.${it.methodName}:${it.lineNumber}"
    }
    runCatching {
        error("Сбой приложения: component=$component, type=${failure.javaClass.name}, stack=$location")
    }
}
