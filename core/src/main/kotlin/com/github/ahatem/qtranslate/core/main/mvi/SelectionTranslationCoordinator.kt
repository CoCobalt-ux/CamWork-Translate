package com.github.ahatem.qtranslate.core.main.mvi

/** Причины фонового сценария, не зависящие от текста исключения конкретного провайдера. */
enum class SelectionTranslationFailureReason {
    DISABLED,
    UNSUPPORTED_DIRECTION,
    NO_TARGET_LANGUAGE,
    NETWORK,
    RATE_LIMIT,
    TIMEOUT,
    AUTHENTICATION,
    INVALID,
    SERVICE_UNAVAILABLE,
    NO_CHANGE,
    CAPTURE,
    PASTE,
    CANCELLED,
    UNKNOWN
}

internal data class SelectionTranslationTicket(
    val requestId: Long,
    val trigger: SelectionTranslationTrigger
)

internal sealed interface SelectionTranslationAdmission {
    data class Accepted(
        val ticket: SelectionTranslationTicket,
        val superseded: SelectionTranslationTicket?
    ) : SelectionTranslationAdmission

    data class Rejected(
        val active: SelectionTranslationTicket
    ) : SelectionTranslationAdmission
}

/**
 * Сериализует только фоновые переводы выделения. Явный Shift имеет приоритет над AUTO,
 * а устаревший ticket больше не может вставить или показать результат.
 */
internal class SelectionTranslationCoordinator {
    private var sequence = 0L
    private var active: SelectionTranslationTicket? = null

    @Synchronized
    fun begin(
        trigger: SelectionTranslationTrigger,
        proposedRequestId: Long = 0L
    ): SelectionTranslationAdmission {
        val current = active
        if (current != null && trigger.priority < current.trigger.priority) {
            return SelectionTranslationAdmission.Rejected(current)
        }

        val requestId = if (proposedRequestId > 0L) {
            sequence = maxOf(sequence, proposedRequestId)
            proposedRequestId
        } else {
            ++sequence
        }
        val ticket = SelectionTranslationTicket(requestId, trigger)
        active = ticket
        return SelectionTranslationAdmission.Accepted(ticket, current)
    }

    @Synchronized
    fun isCurrent(ticket: SelectionTranslationTicket): Boolean = active == ticket

    /** Возвращает true, только если завершился действительно текущий запрос. */
    @Synchronized
    fun complete(ticket: SelectionTranslationTicket): Boolean {
        if (active != ticket) return false
        active = null
        return true
    }

    @Synchronized
    fun cancelAll(): SelectionTranslationTicket? = active.also { active = null }

    private val SelectionTranslationTrigger.priority: Int
        get() = when (this) {
            SelectionTranslationTrigger.AUTO_SELECTION -> 0
            SelectionTranslationTrigger.MANUAL_BUTTON -> 1
            SelectionTranslationTrigger.SHIFT,
            SelectionTranslationTrigger.MANUAL_REPLACE_BUTTON -> 2
        }
}
