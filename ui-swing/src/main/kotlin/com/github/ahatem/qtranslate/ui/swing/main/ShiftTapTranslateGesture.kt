package com.github.ahatem.qtranslate.ui.swing.main

/**
 * Распознаёт безопасный жест быстрого перевода: выделение мышью и короткое одиночное
 * нажатие настроенной modifier-клавиши. По умолчанию это Shift. `null` отключает нативный
 * tap-жест, когда пользователь выбрал обычную комбинацию, которую регистрирует Keymaster.
 * Класс не перехватывает системные клавиши и не зависит от UI, поэтому правила жеста можно
 * полностью проверить модульными тестами.
 */
internal class ShiftTapTranslateGesture(
    private val triggerKeyCode: Int?,
    private val selectionModifierKeyCodes: Set<Int> = emptySet(),
    private val selectAllKeyCode: Int? = null,
    private val maxTapDurationMs: Long = DEFAULT_MAX_TAP_DURATION_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private val pressedKeys = mutableSetOf<Int>()

    private var selectionCompletedAt: Long? = null
    private var triggerPressedAt: Long? = null
    private var triggerIsInvalid = false
    private var lastTriggeredAt: Long? = null
    private var selectAllInProgress = false
    private var selectAllMainKeyReleased = false
    private var selectAllIsInvalid = false

    @Volatile
    var lastDecision: ShiftTapDecision = ShiftTapDecision.NOT_TRIGGER_KEY
        private set

    /** Новое нажатие мыши обычно снимает предыдущее выделение. */
    @Synchronized
    fun onPointerPressed() {
        selectionCompletedAt = null
        if (selectAllInProgress) selectAllIsInvalid = true
        if (triggerPressedAt != null) triggerIsInvalid = true
    }

    /** Перетаскивание с уже зажатым trigger не считается одиночным жестом. */
    @Synchronized
    fun onPointerDragged() {
        if (triggerPressedAt != null) triggerIsInvalid = true
    }

    /** Двойной и тройной клик считаются выделением слова или строки. */
    @Synchronized
    fun onPointerClicked(clickCount: Int, nowMs: Long = System.currentTimeMillis()) {
        if (clickCount >= 2) selectionCompletedAt = nowMs
    }

    @Synchronized
    fun onSelectionCompleted(nowMs: Long = System.currentTimeMillis()) {
        selectionCompletedAt = nowMs
    }

    @Synchronized
    fun onKeyPressed(keyCode: Int, nowMs: Long = System.currentTimeMillis()) {
        // Повтор от удерживаемой клавиши не должен перезапускать таймер.
        if (!pressedKeys.add(keyCode)) return

        if (selectAllInProgress &&
            keyCode != selectAllKeyCode && keyCode !in selectionModifierKeyCodes
        ) {
            selectAllIsInvalid = true
        }

        if (keyCode == selectAllKeyCode &&
            pressedKeys.any { it in selectionModifierKeyCodes } &&
            pressedKeys.all { it == selectAllKeyCode || it in selectionModifierKeyCodes }
        ) {
            selectAllInProgress = true
            selectAllMainKeyReleased = false
            selectAllIsInvalid = false
        }

        // Печатный ввод или навигация после выделения меняют его смысл. Комбинации с Ctrl/Meta
        // не очищают маркер: среди них Ctrl+A и синтетический Ctrl+C для безопасного захвата.
        val selectionModifierDown = pressedKeys.any { it in selectionModifierKeyCodes }
        if (selectionCompletedAt != null &&
            keyCode != triggerKeyCode &&
            keyCode !in selectionModifierKeyCodes &&
            !selectionModifierDown
        ) {
            selectionCompletedAt = null
        }

        if (keyCode == triggerKeyCode) {
            if (selectAllInProgress) selectAllIsInvalid = true
            triggerPressedAt = nowMs
            triggerIsInvalid = pressedKeys.size > 1
        } else if (triggerPressedAt != null) {
            triggerIsInvalid = true
        }
    }

    /** Возвращает true только для короткого изолированного trigger после свежего выделения. */
    @Synchronized
    fun onKeyReleased(keyCode: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (keyCode != triggerKeyCode) {
            pressedKeys.remove(keyCode)
            updateSelectAllOnRelease(keyCode, nowMs)
            if (triggerPressedAt != null) triggerIsInvalid = true
            return false
        }

        val pressedAt = triggerPressedAt
        val selectedAt = selectionCompletedAt
        val otherKeyIsDown = pressedKeys.any { it != triggerKeyCode }

        pressedKeys.remove(triggerKeyCode)
        triggerPressedAt = null

        val tapDuration = pressedAt?.let { nowMs - it }
        val cooldownAge = lastTriggeredAt?.let { nowMs - it }

        lastDecision = when {
            pressedAt == null -> ShiftTapDecision.NOT_TRIGGER_KEY
            triggerIsInvalid || otherKeyIsDown -> ShiftTapDecision.SYSTEM_OR_KEY_CHORD
            tapDuration !in 0..maxTapDurationMs -> ShiftTapDecision.HELD_TOO_LONG
            selectedAt == null -> ShiftTapDecision.NO_SELECTION
            cooldownAge != null && cooldownAge < cooldownMs -> ShiftTapDecision.COOLDOWN
            else -> ShiftTapDecision.TRIGGERED
        }
        val shouldTrigger = lastDecision == ShiftTapDecision.TRIGGERED

        // Одно выделение даёт ровно одну попытку. Даже невалидная комбинация не должна
        // оставлять скрытый триггер, который сработает от следующего случайного нажатия.
        selectionCompletedAt = null
        triggerIsInvalid = false
        if (shouldTrigger) lastTriggeredAt = nowMs

        return shouldTrigger
    }

    @Synchronized
    fun reset() {
        pressedKeys.clear()
        selectionCompletedAt = null
        triggerPressedAt = null
        triggerIsInvalid = false
        lastTriggeredAt = null
        selectAllInProgress = false
        selectAllMainKeyReleased = false
        selectAllIsInvalid = false
        lastDecision = ShiftTapDecision.NOT_TRIGGER_KEY
    }

    /** Ctrl+A/Meta+A считается выделением только после полного отпускания сочетания. */
    private fun updateSelectAllOnRelease(keyCode: Int, nowMs: Long) {
        if (!selectAllInProgress) return
        if (keyCode == selectAllKeyCode) selectAllMainKeyReleased = true

        val modifierStillDown = pressedKeys.any { it in selectionModifierKeyCodes }
        if (!selectAllMainKeyReleased || modifierStillDown) return

        if (!selectAllIsInvalid) selectionCompletedAt = nowMs
        selectAllInProgress = false
        selectAllMainKeyReleased = false
        selectAllIsInvalid = false
    }

    private companion object {
        const val DEFAULT_MAX_TAP_DURATION_MS = 350L
        // Событие выделения одноразовое, поэтому дополнительный cooldown только отбрасывал
        // следующее легитимное быстрое выделение и создавал эффект «срабатывает через раз».
        const val DEFAULT_COOLDOWN_MS = 0L
    }
}

internal enum class ShiftTapDecision {
    TRIGGERED,
    NO_SELECTION,
    HELD_TOO_LONG,
    SYSTEM_OR_KEY_CHORD,
    COOLDOWN,
    NOT_TRIGGER_KEY
}
