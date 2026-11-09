package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.shared.logging.recordFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.retryWhen

/** Ошибка одного события не отключает все последующие переводы до перезапуска. */
internal suspend fun <T> Flow<T>.collectWithRecovery(
    logger: Logger,
    observer: String,
    retryDelayMillis: Long = 250L,
    action: suspend (T) -> Unit
) {
    require(retryDelayMillis > 0)
    fun report(error: Exception) {
        // Сообщение исключения может содержать перевод или ключ: записываем только тип и место.
        logger.recordFailure(observer, error)
    }
    retryWhen { cause, _ ->
        if (cause is CancellationException) currentCoroutineContext().ensureActive()
        if (cause !is Exception) throw cause
        report(cause)
        delay(retryDelayMillis)
        true
    }.collect { value ->
        try {
            action(value)
        } catch (error: Exception) {
            // Таймаут вложенной операции не равен закрытию всего окна/подписки.
            if (error is CancellationException) currentCoroutineContext().ensureActive()
            report(error)
        }
    }
}
