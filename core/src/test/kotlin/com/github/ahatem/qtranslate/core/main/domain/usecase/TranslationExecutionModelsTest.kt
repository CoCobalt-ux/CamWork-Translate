package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationExecutionModelsTest {
    @Test
    fun `ошибки провайдера преобразуются в стабильные причины интерфейса`() {
        assertEquals(
            TranslationFailureKind.NETWORK,
            ServiceError.NetworkError("dns").toTranslationFailureKind()
        )
        assertEquals(
            TranslationFailureKind.RATE_LIMIT,
            ServiceError.RateLimitError("429").toTranslationFailureKind()
        )
        assertEquals(
            TranslationFailureKind.AUTHENTICATION,
            ServiceError.AuthenticationError("401").toTranslationFailureKind()
        )
        assertEquals(
            TranslationFailureKind.INVALID,
            ServiceError.InvalidResponseError("json").toTranslationFailureKind()
        )
    }
}
