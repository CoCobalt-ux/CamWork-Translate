package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.util.shortSummary
import com.github.michaelbull.result.fold
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class PerformSpellCheckUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val logger: Logger = loggerFactory.getLogger("PerformSpellCheckUseCase")
    private val requestMutex = Mutex()
    private val pausedUntil = mutableMapOf<String, Long>()

    private companion object {
        const val SPELL_CHECK_TIMEOUT_MS = 10_000L
        const val DEFAULT_RATE_LIMIT_SECONDS = 60
    }

    /** Фоновые проверки пропускаются во время паузы без повторного уведомления. */
    suspend operator fun invoke(
        currentState: MainState,
        text: String,
        requestedByUser: Boolean = false,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ): List<Correction> {
        if (text.isBlank()) return emptyList()

        val outcome = requestMutex.withLock {
            check(SpellCheckRequest(text, currentState.sourceLanguage), requestedByUser)
        }
        outcome.status?.let { status ->
            val type = if (status is StatusCode.SpellCheckRateLimited) NotificationType.INFO else NotificationType.WARNING
            onStatusUpdate(status, type, true)
        }
        return outcome.corrections
    }

    private suspend fun check(request: SpellCheckRequest, requestedByUser: Boolean): Outcome {
        val active = activeServiceManager.getActive<SpellChecker>(ServiceRole.SPELL_CHECKER) ?: return Outcome()
        val previousPause = pausedUntil[active.id]
        val remaining = previousPause?.minus(clockMillis()) ?: 0L
        if (remaining > 0) {
            val status = if (requestedByUser) StatusCode.SpellCheckRateLimited((remaining + 999L) / 1_000L) else null
            return Outcome(status = status)
        }

        logger.debug("Проверка орфографии: сервис=${active.id}")
        val result = withTimeoutOrNull(SPELL_CHECK_TIMEOUT_MS) { active.service.check(request) }
        if (result == null) {
            logger.warn("Истекло время проверки орфографии: сервис=${active.id}")
            return Outcome(status = StatusCode.SpellCheckTimeout)
        }

        return result.fold(
            success = { response ->
                pausedUntil.remove(active.id)
                Outcome(corrections = response.corrections)
            },
            failure = { error ->
                if (error is ServiceError.RateLimitError) {
                    val seconds = error.retryAfterSeconds?.takeIf { it > 0 } ?: DEFAULT_RATE_LIMIT_SECONDS
                    pausedUntil[active.id] = clockMillis() + seconds * 1_000L
                    if (previousPause == null) {
                        logger.warn("Проверка орфографии приостановлена: сервис=${active.id}, пауза=${seconds}с")
                    }
                    val status = if (previousPause == null || requestedByUser) {
                        StatusCode.SpellCheckRateLimited(seconds.toLong())
                    } else null
                    Outcome(status = status)
                } else {
                    logger.error("Сбой проверки орфографии: сервис=${active.id}, тип=${error::class.simpleName}")
                    Outcome(status = StatusCode.SpellCheckFailed(error.shortSummary()))
                }
            }
        )
    }

    private data class Outcome(
        val corrections: List<Correction> = emptyList(),
        val status: StatusCode? = null
    )
}
