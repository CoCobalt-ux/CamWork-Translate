package com.github.ahatem.qtranslate.core.main.mvi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstantTranslationCoordinatorTest {
    @Test
    fun `явный Translate подавляет отложенный перевод той же редакции`() {
        val coordinator = InstantTranslationCoordinator()
        val revision = coordinator.recordUserInput("Привет")

        coordinator.markCurrentAsExplicit()

        assertFalse(coordinator.shouldAutoTranslate(revision))
    }

    @Test
    fun `следующая редакция пользователя снова допускает мгновенный перевод`() {
        val coordinator = InstantTranslationCoordinator()
        val first = coordinator.recordUserInput("Прив")
        coordinator.markCurrentAsExplicit()
        val second = coordinator.recordUserInput("Привет")

        assertFalse(coordinator.shouldAutoTranslate(first))
        assertTrue(coordinator.shouldAutoTranslate(second))
    }
}
