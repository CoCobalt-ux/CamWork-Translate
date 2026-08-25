package com.github.ahatem.qtranslate.core.main.mvi

import java.util.concurrent.atomic.AtomicLong

internal data class UserInputRevision(
    val id: Long,
    val text: String
)

/** Отличает ввод пользователя от программного обновления MainState и подавляет debounce-дубликат. */
internal class InstantTranslationCoordinator {
    private val revision = AtomicLong(0)
    private val explicitlyHandled = AtomicLong(0)

    fun recordUserInput(text: String): UserInputRevision =
        UserInputRevision(revision.incrementAndGet(), text)

    fun markCurrentAsExplicit(): Long {
        val current = revision.get()
        explicitlyHandled.accumulateAndGet(current, ::maxOf)
        return current
    }

    fun shouldAutoTranslate(input: UserInputRevision): Boolean =
        input.id > explicitlyHandled.get()
}
