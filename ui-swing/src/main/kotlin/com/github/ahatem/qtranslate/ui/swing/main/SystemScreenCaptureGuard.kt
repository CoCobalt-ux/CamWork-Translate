package com.github.ahatem.qtranslate.ui.swing.main

/** Screenshot clipboard имеет приоритет над восстановлением временного текста для Ctrl+V. */
internal fun shouldRestorePasteClipboard(
    ownsTranslationClipboard: Boolean,
    isScreenCaptureSuppressed: Boolean
): Boolean = ownsTranslationClipboard && !isScreenCaptureSuppressed

/**
 * Не даёт пассивному захвату выделенного текста вмешиваться в системный снимок экрана.
 *
 * CamWork наблюдает за выделением мышью и обычно после отпускания кнопки отправляет `Ctrl+C`.
 * Для `Win+Shift+S` то же движение мыши является выбором области снимка, поэтому синтетическое
 * копирование закрывает или портит Snipping Tool. Этот класс распознаёт системный жест, блокирует
 * обработку его мыши и оставляет короткий запас после завершения области.
 */
internal class SystemScreenCaptureGuard(
    private val metaKeyCode: Int,
    private val shiftKeyCode: Int,
    private val snippingKeyCode: Int,
    private val printScreenKeyCode: Int,
    private val escapeKeyCode: Int,
    private val regionTimeoutMs: Long = REGION_TIMEOUT_MS,
    private val printScreenTimeoutMs: Long = PRINT_SCREEN_INITIAL_TIMEOUT_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    private val pressedKeys = mutableSetOf<Int>()
    private var suppressionUntilMs = 0L
    private var cooldownUntilMs = 0L
    private var regionCaptureActive = false
    private var regionDragObserved = false
    private var finishOnAnyPointerRelease = false

    @Synchronized
    fun onKeyPressed(keyCode: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        pressedKeys.add(keyCode)

        when {
            keyCode == snippingKeyCode &&
                metaKeyCode in pressedKeys &&
                shiftKeyCode in pressedKeys -> {
                regionCaptureActive = true
                regionDragObserved = false
                finishOnAnyPointerRelease = false
                suppressionUntilMs = nowMs + regionTimeoutMs
            }

            keyCode == printScreenKeyCode -> {
                // В Windows 11 Print Screen обычно открывает Snipping Tool, а не делает
                // мгновенный полный снимок. Пользователь может выбирать область дольше двух
                // секунд, поэтому ждём завершения мышью так же, как для Win+Shift+S.
                regionCaptureActive = true
                regionDragObserved = false
                finishOnAnyPointerRelease = true
                suppressionUntilMs = nowMs + printScreenTimeoutMs
            }

            keyCode == escapeKeyCode && isSuppressedInternal(nowMs) -> finishCapture(nowMs)
        }

        return isSuppressedInternal(nowMs)
    }

    @Synchronized
    fun onKeyReleased(keyCode: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        pressedKeys.remove(keyCode)
        return isSuppressedInternal(nowMs)
    }

    @Synchronized
    fun onPointerPressed(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isSuppressedInternal(nowMs)) return false
        if (regionCaptureActive) {
            regionDragObserved = false
            // PrintScreen неоднозначен: полный снимок требует короткой защиты, а Windows 11
            // может открыть выбор области. Первый pointer подтверждает второй сценарий.
            if (finishOnAnyPointerRelease) suppressionUntilMs = nowMs + regionTimeoutMs
        }
        return true
    }

    @Synchronized
    fun onPointerDragged(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isSuppressedInternal(nowMs)) return false
        if (regionCaptureActive) regionDragObserved = true
        return true
    }

    @Synchronized
    fun onPointerReleased(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isSuppressedInternal(nowMs)) return false
        if (regionCaptureActive && (regionDragObserved || finishOnAnyPointerRelease)) {
            finishCapture(nowMs)
        }
        return true
    }

    @Synchronized
    fun isSuppressed(nowMs: Long = System.currentTimeMillis()): Boolean =
        isSuppressedInternal(nowMs)

    @Synchronized
    fun reset() {
        pressedKeys.clear()
        suppressionUntilMs = 0L
        cooldownUntilMs = 0L
        regionCaptureActive = false
        regionDragObserved = false
        finishOnAnyPointerRelease = false
    }

    private fun isSuppressedInternal(nowMs: Long): Boolean {
        if (regionCaptureActive && nowMs > suppressionUntilMs) {
            regionCaptureActive = false
            regionDragObserved = false
            finishOnAnyPointerRelease = false
            suppressionUntilMs = 0L
        }
        return nowMs <= suppressionUntilMs || nowMs <= cooldownUntilMs
    }

    private fun finishCapture(nowMs: Long) {
        regionCaptureActive = false
        regionDragObserved = false
        finishOnAnyPointerRelease = false
        suppressionUntilMs = 0L
        cooldownUntilMs = nowMs + cooldownMs
    }

    private companion object {
        /** Пользователь может не сразу начать выделять область в Snipping Tool. */
        const val REGION_TIMEOUT_MS = 30_000L

        /** Полный PrintScreen не должен отключать Shift и выделение на следующие 30 секунд. */
        const val PRINT_SCREEN_INITIAL_TIMEOUT_MS = 4_000L

        /** Не даёт событию клика после отпускания области снова запустить захват текста. */
        const val COOLDOWN_MS = 900L
    }
}
