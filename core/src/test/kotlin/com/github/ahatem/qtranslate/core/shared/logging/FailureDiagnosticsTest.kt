package com.github.ahatem.qtranslate.core.shared.logging

import com.github.ahatem.qtranslate.api.core.Logger
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailureDiagnosticsTest {
    @Test
    fun `журнал содержит место сбоя без закрытого текста провайдера`() {
        var recorded = ""
        var recordedCause: Throwable? = IllegalArgumentException("Начальное значение")
        val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) {
                recorded = message
                recordedCause = error
            }
        }
        logger.recordFailure("проверка", IllegalStateException("Закрытый перевод и секретный ключ"))
        assertNull(recordedCause)
        assertTrue("IllegalStateException" in recorded)
        assertTrue("FailureDiagnosticsTest" in recorded)
        assertTrue("Закрытый перевод" !in recorded)
        assertTrue("секретный ключ" !in recorded)
    }
}
