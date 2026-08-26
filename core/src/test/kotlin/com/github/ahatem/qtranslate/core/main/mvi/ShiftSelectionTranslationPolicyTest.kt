package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.core.settings.data.ShiftTapTranslationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShiftSelectionTranslationPolicyTest {
    @Test
    fun `BIDIRECTIONAL Shift заменяет текст во всех определённых направлениях`() {
        ShiftSelectionDirection.entries.forEach { direction ->
            assertEquals(
                SelectionTranslationAction.REPLACE,
                resolveSelectionTranslationAction(
                    trigger = SelectionTranslationTrigger.SHIFT,
                    mode = ShiftTapTranslationMode.BIDIRECTIONAL,
                    direction = direction
                ),
                "Направление $direction не должно открывать passive overlay"
            )
        }
    }

    @Test
    fun `REPLACE ONLY заменяет только исходящий текст модели`() {
        assertEquals(
            SelectionTranslationAction.REPLACE,
            resolveSelectionTranslationAction(
                SelectionTranslationTrigger.SHIFT,
                ShiftTapTranslationMode.REPLACE_ONLY,
                ShiftSelectionDirection.MODEL_LANGUAGE
            )
        )
        listOf(ShiftSelectionDirection.FOREIGN_LANGUAGE, ShiftSelectionDirection.AMBIGUOUS)
            .forEach { direction ->
                assertEquals(
                    SelectionTranslationAction.IGNORE,
                    resolveSelectionTranslationAction(
                        SelectionTranslationTrigger.SHIFT,
                        ShiftTapTranslationMode.REPLACE_ONLY,
                        direction
                    )
                )
            }
    }

    @Test
    fun `OVERLAY ONLY сохраняет прежний пассивный сценарий`() {
        ShiftSelectionDirection.entries.forEach { direction ->
            assertEquals(
                SelectionTranslationAction.PASSIVE_OVERLAY,
                resolveSelectionTranslationAction(
                    SelectionTranslationTrigger.SHIFT,
                    ShiftTapTranslationMode.OVERLAY_ONLY,
                    direction
                )
            )
        }
    }

    @Test
    fun `auto selection не заменяет текст и игнорирует исходящий язык модели`() {
        assertEquals(
            SelectionTranslationAction.IGNORE,
            resolveSelectionTranslationAction(
                SelectionTranslationTrigger.AUTO_SELECTION,
                ShiftTapTranslationMode.BIDIRECTIONAL,
                ShiftSelectionDirection.MODEL_LANGUAGE
            )
        )
        assertEquals(
            SelectionTranslationAction.PASSIVE_OVERLAY,
            resolveSelectionTranslationAction(
                SelectionTranslationTrigger.AUTO_SELECTION,
                ShiftTapTranslationMode.BIDIRECTIONAL,
                ShiftSelectionDirection.FOREIGN_LANGUAGE
            )
        )
    }

    @Test
    fun `явный Shift не подавляется недавним автоматическим переводом`() {
        assertFalse(
            shouldSuppressRecentPassiveOverlay(
                trigger = SelectionTranslationTrigger.SHIFT,
                wasRecentlyShown = true
            )
        )
    }

    @Test
    fun `повторный автоматический overlay подавляется`() {
        assertTrue(
            shouldSuppressRecentPassiveOverlay(
                trigger = SelectionTranslationTrigger.AUTO_SELECTION,
                wasRecentlyShown = true
            )
        )
    }

    @Test
    fun `явная мини кнопка не подавляется недавним автоматическим переводом`() {
        assertFalse(
            shouldSuppressRecentPassiveOverlay(
                trigger = SelectionTranslationTrigger.MANUAL_BUTTON,
                wasRecentlyShown = true
            )
        )
    }

    @Test
    fun `последний автоматический запрос скрывает свой индикатор`() {
        assertTrue(
            shouldDismissAutomaticSelectionProgress(
                requestGeneration = 12,
                currentGeneration = 12
            )
        )
    }

    @Test
    fun `устаревший запрос не скрывает индикатор нового выделения`() {
        assertFalse(
            shouldDismissAutomaticSelectionProgress(
                requestGeneration = 12,
                currentGeneration = 13
            )
        )
    }

    @Test
    fun `замена сохраняет пробелы и переводы строк по краям выделения`() {
        assertEquals(
            "  Hello\r\n",
            restoreSelectionBoundaryWhitespace("  Привет\r\n", "Hello")
        )
    }

    @Test
    fun `кнопка замены всегда заменяет независимо от настройки Shift`() {
        ShiftTapTranslationMode.entries.forEach { mode ->
            ShiftSelectionDirection.entries.forEach { direction ->
                assertEquals(
                    SelectionTranslationAction.REPLACE,
                    resolveSelectionTranslationAction(
                        SelectionTranslationTrigger.MANUAL_REPLACE_BUTTON,
                        mode,
                        direction
                    )
                )
            }
        }
    }

    @Test
    fun `неизменённый ответ имеет отдельную причину NO CHANGE`() =
        kotlinx.coroutines.test.runTest {
            val result = executeSelectionTranslation(
                translationInput = "Hello",
                action = SelectionTranslationAction.REPLACE,
                translate = { SelectionTranslationAttempt.Translated("Hello") },
                canDeliver = { true },
                onReplace = { error("Неизменённый текст нельзя вставлять") },
                onPassiveOverlay = { error("Неизменённый текст нельзя показывать") }
            )

            assertEquals(
                SelectionTranslationExecution.FAILED(
                    SelectionTranslationFailureReason.NO_CHANGE
                ),
                result
            )
        }
}
