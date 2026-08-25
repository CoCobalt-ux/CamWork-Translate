package com.github.ahatem.qtranslate.core.main.mvi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelectionTranslationCoordinatorTest {
    @Test
    fun `AUTO не может вытеснить активный Shift`() {
        val coordinator = SelectionTranslationCoordinator()
        val shift = assertIs<SelectionTranslationAdmission.Accepted>(
            coordinator.begin(SelectionTranslationTrigger.SHIFT, 41)
        ).ticket

        val rejected = assertIs<SelectionTranslationAdmission.Rejected>(
            coordinator.begin(SelectionTranslationTrigger.AUTO_SELECTION, 42)
        )

        assertEquals(shift, rejected.active)
        assertTrue(coordinator.isCurrent(shift))
    }

    @Test
    fun `Shift вытесняет AUTO и запрещает устаревшую доставку`() {
        val coordinator = SelectionTranslationCoordinator()
        val automatic = assertIs<SelectionTranslationAdmission.Accepted>(
            coordinator.begin(SelectionTranslationTrigger.AUTO_SELECTION, 10)
        ).ticket
        val shiftAdmission = assertIs<SelectionTranslationAdmission.Accepted>(
            coordinator.begin(SelectionTranslationTrigger.SHIFT, 11)
        )

        assertEquals(automatic, shiftAdmission.superseded)
        assertFalse(coordinator.isCurrent(automatic))
        assertTrue(coordinator.isCurrent(shiftAdmission.ticket))
        assertFalse(coordinator.complete(automatic))
    }

    @Test
    fun `новейший AUTO вытесняет только предыдущий AUTO`() {
        val coordinator = SelectionTranslationCoordinator()
        val first = assertIs<SelectionTranslationAdmission.Accepted>(
            coordinator.begin(SelectionTranslationTrigger.AUTO_SELECTION)
        ).ticket
        val second = assertIs<SelectionTranslationAdmission.Accepted>(
            coordinator.begin(SelectionTranslationTrigger.AUTO_SELECTION)
        )

        assertEquals(first, second.superseded)
        assertTrue(coordinator.complete(second.ticket))
    }
}
