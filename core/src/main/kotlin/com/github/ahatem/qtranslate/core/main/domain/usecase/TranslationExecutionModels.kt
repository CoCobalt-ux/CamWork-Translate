package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.plugin.ServiceError

/** Независимые каналы отмены: новый запрос отменяет только предшественника того же сценария. */
enum class TranslationLane {
    MAIN,
    SELECTION_EXPLICIT,
    SELECTION_AUTO,
    LIVE_LENS
}

/** Стабильная причина завершения, которую MVI может показать без разбора текста исключения. */
enum class TranslationFailureKind {
    NETWORK,
    RATE_LIMIT,
    TIMEOUT,
    AUTHENTICATION,
    INVALID,
    SERVICE_UNAVAILABLE,
    CANCELLED,
    UNKNOWN
}

/** Безопасный диагностический контекст. Содержимое переводимого текста сюда не входит. */
data class TranslationRequestContext(
    val requestId: Long,
    val origin: String,
    val attempt: Int = 1
)

/** Типизированный итог одного логического запроса перевода. */
sealed interface TranslationRunResult {
    data class Success(
        val translatedText: String,
        val translatorId: String,
        val translatorName: String
    ) : TranslationRunResult

    data class Failure(
        val kind: TranslationFailureKind,
        val translatorId: String? = null,
        val translatorName: String? = null
    ) : TranslationRunResult
}

internal fun ServiceError.toTranslationFailureKind(): TranslationFailureKind = when (this) {
    is ServiceError.NetworkError -> TranslationFailureKind.NETWORK
    is ServiceError.RateLimitError -> TranslationFailureKind.RATE_LIMIT
    is ServiceError.TimeoutError -> TranslationFailureKind.TIMEOUT
    is ServiceError.ConfigurationError,
    is ServiceError.AuthenticationError -> TranslationFailureKind.AUTHENTICATION
    is ServiceError.InvalidInputError,
    is ServiceError.InvalidResponseError,
    is ServiceError.ValidationError,
    is ServiceError.UnsupportedLanguageError -> TranslationFailureKind.INVALID
    is ServiceError.ServiceUnavailableError -> TranslationFailureKind.SERVICE_UNAVAILABLE
    is ServiceError.UnknownError -> TranslationFailureKind.UNKNOWN
}
