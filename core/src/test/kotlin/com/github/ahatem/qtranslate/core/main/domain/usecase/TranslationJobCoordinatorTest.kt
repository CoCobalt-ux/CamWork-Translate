package com.github.ahatem.qtranslate.core.main.domain.usecase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationJobCoordinatorTest {
    @Test
    fun `новый MAIN не отменяет явный Shift`() = runTest {
        val coordinator = TranslationJobCoordinator(this)
        val shiftCompleted = CompletableDeferred<Unit>()

        coordinator.launchLatest(TranslationLane.SELECTION_EXPLICIT, "new shift") {
            shiftCompleted.complete(Unit)
        }
        coordinator.launchLatest(TranslationLane.MAIN, "new main") { }

        testScheduler.advanceUntilIdle()
        assertTrue(shiftCompleted.isCompleted)
    }

    @Test
    fun `новый запрос отменяет только предшественника своего канала`() = runTest {
        val coordinator = TranslationJobCoordinator(this)
        val firstAutoCancelled = CompletableDeferred<Unit>()
        val explicitCancelled = CompletableDeferred<Unit>()

        coordinator.launchLatest(TranslationLane.SELECTION_AUTO, "new auto") {
            try {
                awaitCancellation()
            } finally {
                firstAutoCancelled.complete(Unit)
            }
        }
        coordinator.launchLatest(TranslationLane.SELECTION_EXPLICIT, "new shift") {
            try {
                awaitCancellation()
            } finally {
                explicitCancelled.complete(Unit)
            }
        }
        testScheduler.runCurrent()

        coordinator.launchLatest(TranslationLane.SELECTION_AUTO, "new auto") { }
        testScheduler.runCurrent()

        assertTrue(firstAutoCancelled.isCompleted)
        assertFalse(explicitCancelled.isCompleted)
        coordinator.cancel(TranslationLane.SELECTION_EXPLICIT, "test complete")
    }
}
