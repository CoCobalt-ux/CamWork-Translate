package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent
import com.github.kwhat.jnativehook.mouse.NativeMouseInputListener
import com.tulskiy.keymaster.common.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.MouseInfo
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/**
 * A lone Shift cannot be registered reliably through Keymaster on every platform. It is
 * recognised from JNativeHook events by the guarded selection-tap gesture. Other user-assigned
 * keys and combinations continue through Keymaster.
 */
private val MODIFIER_ONLY_KEY_CODES = setOf(
    KeyEvent.VK_SHIFT,
    KeyEvent.VK_CONTROL,
    KeyEvent.VK_ALT,
    KeyEvent.VK_META,
    KeyEvent.VK_ALT_GRAPH
)

internal fun HotkeyBinding.isModifierOnlySelectionBinding(): Boolean =
    action == HotkeyAction.REPLACE_WITH_TRANSLATION &&
        modifiers == 0 &&
        keyCode in MODIFIER_ONLY_KEY_CODES

internal fun HotkeyBinding.nativeSelectionTapKeyCodeOrNull(): Int? {
    if (action != HotkeyAction.REPLACE_WITH_TRANSLATION ||
        !isEnabled ||
        scope != HotkeyScope.GLOBAL ||
        modifiers != 0
    ) return null

    return NativeKeyEvent.VC_SHIFT.takeIf { keyCode == KeyEvent.VK_SHIFT }
}

private fun createSelectionTapGesture(binding: HotkeyBinding?): ShiftTapTranslateGesture =
    ShiftTapTranslateGesture(
        triggerKeyCode = binding?.nativeSelectionTapKeyCodeOrNull(),
        selectionModifierKeyCodes = setOf(NativeKeyEvent.VC_CONTROL, NativeKeyEvent.VC_META),
        selectAllKeyCode = NativeKeyEvent.VC_A
    )

/**
 * Manages global and local hotkey registration.
 *
 * ### Scopes
 * - [HotkeyScope.GLOBAL] — registered with jKeymaster, fires system-wide
 *   even when CamWork Translate is not focused.
 * - [HotkeyScope.LOCAL] — the caller (MainAppFrame) registers these via
 *   Swing InputMap/ActionMap; this listener only provides the binding list
 *   via [getLocalBindings]. Local bindings never intercept keys from other apps.
 *
 * ### Per-action scope
 * Users can choose per action whether it should be global or local. This prevents
 * shortcuts like Ctrl+L from being stolen from browsers when set to LOCAL.
 *
 * ### Actions
 * - [HotkeyAction.SHOW_MAIN_WINDOW] — special: uses double-Ctrl via JNativeHook,
 *   not a regular KeyStroke. Always GLOBAL. Respects [isEnabled] on the binding.
 * - [HotkeyAction.REPLACE_WITH_TRANSLATION] — copies selected text, translates,
 *   pastes result back via [onReplaceWithTranslation].
 * - [HotkeyAction.CYCLE_TARGET_LANGUAGE] — default LOCAL, cycles the target language.
 */
class MainGlobalKeyListener(
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val onShowApp: (String) -> Unit,
    private val onShowQuickTranslate: (String) -> Unit,
    /** Единый двунаправленный сценарий настраиваемого quick-selection hotkey. */
    private val onShiftTapTranslate: (String, Long) -> Unit,
    private val onListenToText: (String) -> Unit,
    private val onOpenSnippingTool: () -> Unit,
    @Suppress("unused")
    private val onReplaceWithTranslation: (String) -> Unit,
    private val onCycleTargetLanguage: () -> Unit,
    private val onShowDictionary: (String) -> Unit = {},
    private val onShowImages: (String) -> Unit = {},
    private val onTranslate: () -> Unit = {},
    /** Mini-button для EDITABLE/UNKNOWN или READ_ONLY при выключенном auto-overlay. */
    private val onSelectionDetected: (String, Point) -> Unit = { _, _ -> },
    /** Автоматический пассивный перевод после подтверждённого выделения мышью. */
    private val onAutoTranslateSelection: (String, Long) -> Unit = { _, _ -> },
    private val onPointerPressed: (Point) -> Unit = {},
    /** Вызывается сразу при отпускании короткого Shift, ещё до чтения clipboard. */
    private val onShiftTapStarted: (Long) -> Unit = {},
    /** Инвалидирует ожидающий перевод/вставку до открытия системного screenshot UI. */
    private val onSystemScreenCaptureStarted: () -> Unit = {}
) {

    private var provider: Provider? = null
    private var nativeHookRegistered = false
    private val sequenceListener = CustomSequenceListener()
    private val selectionMouseListener = SelectionMouseListener()
    private val selectionSurfaceDetector = WindowsSelectionSurfaceDetector()
    @Volatile
    private var shiftTapGesture = createSelectionTapGesture(
        HotkeyBinding.DEFAULT_SELECTION_TRANSLATION
    )
    private val systemScreenCaptureGuard = SystemScreenCaptureGuard(
        metaKeyCode = NativeKeyEvent.VC_META,
        shiftKeyCode = NativeKeyEvent.VC_SHIFT,
        snippingKeyCode = NativeKeyEvent.VC_S,
        printScreenKeyCode = NativeKeyEvent.VC_PRINTSCREEN,
        escapeKeyCode = NativeKeyEvent.VC_ESCAPE
    )
    /** Очередь вместо try-lock: конкурирующие чтения выделения больше не теряются молча. */
    private val clipboardMutex = Mutex()
    private val clipboardInterceptionGuard = ClipboardInterceptionGuard()
    private val syntheticCopyModifierGate = SyntheticCopyModifierGate()
    private var selectionCaptureJob: Job? = null
    private val hotkeysEnabled = AtomicBoolean(true)
    // AtomicBoolean.compareAndSet prevents double-initialization if initialize()
    // is called concurrently (e.g. from two rapid lifecycle events).
    private val initialized = AtomicBoolean(false)
    private val autoSelectionTranslateEnabled = AtomicBoolean(true)
    private val shiftTapTranslateEnabled = AtomicBoolean(true)
    private val requestSequence = AtomicLong(0)

    @Volatile private var bindings: List<HotkeyBinding> = HotkeyBinding.DEFAULTS

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            initJKeyMaster()
            initJNativeHook()
        } catch (e: Exception) {
            initialized.set(false)   // allow retry if initialization itself failed
            logger.error("Hotkey initialization failed", e)
        }
    }

    fun updateBindings(newBindings: List<HotkeyBinding>) {
        bindings = newBindings
        val previousGesture = shiftTapGesture
        shiftTapGesture = createSelectionTapGesture(
            newBindings.find { it.action == HotkeyAction.REPLACE_WITH_TRANSLATION }
        )
        previousGesture.reset()
        if (!initialized.get()) return
        try {
            provider?.reset()
            registerGlobalHotkeys()
        } catch (e: Exception) {
            logger.error("Failed to update hotkey bindings", e)
        }
    }

    fun setHotkeysEnabled(enabled: Boolean) {
        if (!initialized.get()) return
        if (hotkeysEnabled.getAndSet(enabled) == enabled) return
        if (!enabled) {
            shiftTapGesture.reset()
            systemScreenCaptureGuard.reset()
            clipboardInterceptionGuard.invalidateForScreenCapture()
        }
        if (enabled) enableHotkeys() else disableHotkeys()
    }

    /**
     * Сохранено для совместимости со старой настройкой.
     * Mini-button теперь определяется безопасной surface×tray матрицей.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setSelectionIconEnabled(enabled: Boolean) {
        // Намеренно не гейтит capture: OFF + READ_ONLY обязан показать mini-button.
    }

    /** Включает автоматический пассивный перевод выделения мышью. */
    fun setAutoSelectionTranslateEnabled(enabled: Boolean) {
        autoSelectionTranslateEnabled.set(enabled)
    }

    /** Включает безопасный быстрый перевод по короткому Shift после выделения мышью. */
    fun setShiftTapTranslateEnabled(enabled: Boolean) {
        shiftTapTranslateEnabled.set(enabled)
        if (!enabled) shiftTapGesture.reset()
    }

    /**
     * Returns bindings with [HotkeyScope.LOCAL] scope that are enabled and have a key.
     * The caller (MainAppFrame) registers these via Swing InputMap.
     */
    fun getLocalBindings(): List<HotkeyBinding> =
        bindings.filter { it.scope == HotkeyScope.LOCAL && it.isEnabled && it.hasBinding }

    fun shutdown() {
        if (!initialized.get()) return
        selectionCaptureJob?.cancel()
        clipboardInterceptionGuard.invalidateForScreenCapture()
        try {
            provider?.reset()
            provider?.stop()
            provider = null
            if (nativeHookRegistered) {
                GlobalScreen.removeNativeKeyListener(sequenceListener)
                GlobalScreen.removeNativeMouseListener(selectionMouseListener)
                GlobalScreen.removeNativeMouseMotionListener(selectionMouseListener)
                GlobalScreen.unregisterNativeHook()
                nativeHookRegistered = false
            }
        } catch (e: Exception) {
            logger.error("Hotkey manager shutdown error", e)
        } finally {
            initialized.set(false)
        }
    }

    private fun initJKeyMaster() {
        provider = Provider.getCurrentProvider(false)
            ?: throw Exception("Hotkey provider unavailable")
        registerGlobalHotkeys()
    }

    /**
     * Registers only [HotkeyScope.GLOBAL] bindings with jKeymaster.
     * LOCAL bindings are handled by MainAppFrame via Swing InputMap.
     *
     * Each binding is wrapped in its own try-catch so a single failure
     * (e.g. OS refuses to grant a reserved key combination) does not
     * silently abort registration of the remaining bindings.
     */
    private fun registerGlobalHotkeys() {
        val p = provider ?: return

        bindings
            .filter { it.scope == HotkeyScope.GLOBAL && it.isEnabled && it.hasBinding }
            // A lone Shift is handled by the guarded native tap gesture. Asking Keymaster to
            // register it either fails or turns the modifier into a system-wide grab.
            .filter { !it.isModifierOnlySelectionBinding() }
            .forEach { binding ->
                val keyStroke = binding.toKeyStroke() ?: return@forEach
                val action = binding.action
                runCatching {
                    p.register(keyStroke) {
                        if (!hotkeysEnabled.get()) return@register
                        dispatchAction(action)
                    }
                    logger.debug("Registered global hotkey ${action.name}: $keyStroke")
                }.onFailure { ex ->
                    logger.warn(
                        "Failed to register global hotkey ${action.name} ($keyStroke): ${ex.message}"
                    )
                }
            }
    }

    /**
     * Dispatches an action from either a global hotkey or a local InputMap trigger.
     * Called from both [registerGlobalHotkeys] and MainAppFrame's local key handler.
     */
    fun dispatchAction(action: HotkeyAction) {
        when (action) {
            HotkeyAction.SHOW_QUICK_TRANSLATE ->
                scope.launch { handleSelectedText(onShowQuickTranslate) }
            HotkeyAction.LISTEN_TO_TEXT ->
                scope.launch { handleSelectedText(onListenToText) }
            HotkeyAction.OPEN_OCR ->
                onOpenSnippingTool()
            HotkeyAction.SHOW_MAIN_WINDOW ->
                scope.launch { handleSelectedText(onShowApp) }
            HotkeyAction.REPLACE_WITH_TRANSLATION ->
                triggerConfiguredSelectionTranslation()
            HotkeyAction.CYCLE_TARGET_LANGUAGE ->
                onCycleTargetLanguage()
            HotkeyAction.SHOW_DICTIONARY ->
                scope.launch { handleSelectedText(onShowDictionary) }
            HotkeyAction.SHOW_IMAGES ->
                scope.launch { handleSelectedText(onShowImages) }
            HotkeyAction.TRANSLATE ->
                onTranslate()
            // Focus actions are LOCAL-scope only — handled by MainContentView's InputMap.
            // Nothing to do here; the branch is required for exhaustive when.
            HotkeyAction.FOCUS_INPUT,
            HotkeyAction.FOCUS_OUTPUT,
            HotkeyAction.FOCUS_EXTRA_OUTPUT,
            // Likewise LOCAL-only; MainAppFrame handles these because each needs a dialog,
            // the clipboard, or the content view.
            HotkeyAction.COPY_TRANSLATION,
            HotkeyAction.CLEAR_INPUT,
            HotkeyAction.SWAP_LANGUAGES,
            HotkeyAction.OPEN_SETTINGS,
            HotkeyAction.SHOW_HISTORY,
            HotkeyAction.TRANSLATE_DOCUMENT -> Unit
        }
    }

    private fun enableHotkeys() {
        try { provider?.reset(); registerGlobalHotkeys() }
        catch (e: Exception) { logger.error("Enable hotkeys failed", e) }
    }

    private fun disableHotkeys() {
        try { provider?.reset() }
        catch (e: Exception) { logger.error("Disable hotkeys failed", e) }
    }

    private fun initJNativeHook() {
        try {
            if (!nativeHookRegistered) {
                GlobalScreen.registerNativeHook()
                nativeHookRegistered = true
            }
            GlobalScreen.addNativeKeyListener(sequenceListener)
            GlobalScreen.addNativeMouseListener(selectionMouseListener)
            GlobalScreen.addNativeMouseMotionListener(selectionMouseListener)
        } catch (ex: NativeHookException) {
            throw Exception("Native hook registration failed", ex)
        }
    }

    /**
     * Handles the double-Ctrl sequence for [HotkeyAction.SHOW_MAIN_WINDOW].
     *
     * This cannot be expressed as a single KeyStroke so it uses JNativeHook's
     * raw key events. It only fires if:
     * 1. Global hotkeys are enabled
     * 2. The SHOW_MAIN_WINDOW binding exists AND isEnabled = true
     *    (Hoyeun's request — user can disable it from the keyboard panel)
     */
    private inner class CustomSequenceListener : NativeKeyListener {
        private var lastCtrlTime = 0L
        private val threshold = 400

        /** True once a key other than Ctrl is pressed while Ctrl is held. */
        private var ctrlWasPartOfCombination = false
        private var ctrlIsDown = false

        override fun nativeKeyPressed(e: NativeKeyEvent) {
            val wasScreenCaptureSuppressed = systemScreenCaptureGuard.isSuppressed()
            if (systemScreenCaptureGuard.onKeyPressed(e.keyCode)) {
                if (!wasScreenCaptureSuppressed) onSystemScreenCaptureStarted()
                abortClipboardInterceptionForScreenCapture()
                shiftTapGesture.reset()
                resetCtrlSequence()
                return
            }

            if (e.keyCode == NativeKeyEvent.VC_SHIFT) {
                // Выделение мышью запускает фоновое чтение clipboard. Если пользователь сразу
                // выбирает сценарий Shift, отменяем это чтение до того, как его Ctrl+C сможет
                // превратиться в браузерный Ctrl+Shift+C и открыть DevTools.
                selectionCaptureJob?.cancel()
            }
            shiftTapGesture.onKeyPressed(e.keyCode)

            if (e.keyCode == NativeKeyEvent.VC_CONTROL) {
                ctrlIsDown = true
                ctrlWasPartOfCombination = false
            } else if (ctrlIsDown) {
                ctrlWasPartOfCombination = true
            }
        }

        override fun nativeKeyReleased(e: NativeKeyEvent) {
            if (systemScreenCaptureGuard.onKeyReleased(e.keyCode)) {
                shiftTapGesture.reset()
                resetCtrlSequence()
                return
            }
            val shouldTranslateSelection = shiftTapGesture.onKeyReleased(e.keyCode)
            if (shouldTranslateSelection && hotkeysEnabled.get() && shiftTapTranslateEnabled.get()) {
                triggerConfiguredSelectionTranslation()
            } else if (e.keyCode == NativeKeyEvent.VC_SHIFT &&
                shiftTapGesture.lastDecision != ShiftTapDecision.NOT_TRIGGER_KEY
            ) {
                logger.debug(
                    "Selection tap rejected: reason=${shiftTapGesture.lastDecision}"
                )
            }

            if (e.keyCode != NativeKeyEvent.VC_CONTROL) return
            ctrlIsDown = false

            // The Ctrl that ends a shortcut is not a tap. Every hotkey in this application is a
            // Ctrl combination, so counting those releases meant two shortcuts pressed within the
            // threshold — Ctrl+Q twice, or Ctrl+Q then Ctrl+D — read as a double-Ctrl and summoned
            // the main window on top of the popup the user actually asked for.
            //
            // The time is cleared as well as ignored, so the release that ends a shortcut cannot
            // pair with a genuine tap that follows it either.
            if (ctrlWasPartOfCombination) {
                ctrlWasPartOfCombination = false
                lastCtrlTime = 0L
                return
            }

            if (!hotkeysEnabled.get()) return

            // Only fire if the binding exists, is enabled, AND the user
            // has not opted out of the double-Ctrl mechanism specifically.
            val binding = bindings.find { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
            if (binding == null || !binding.isEnabled || !binding.isDoubleCtrlEnabled) return

            val now = System.currentTimeMillis()
            if (now - lastCtrlTime < threshold) {
                scope.launch { handleSelectedText(onShowApp) }
                lastCtrlTime = 0L
                return
            }
            lastCtrlTime = now
        }

        private fun resetCtrlSequence() {
            ctrlWasPartOfCombination = false
            ctrlIsDown = false
            lastCtrlTime = 0L
        }
    }

    /**
     * Detects a drag-to-select gesture in any application and reports the selected
     * text via [onSelectionDetected] so the caller can offer a floating translate button.
     *
     * A click alone is not a selection, so the gesture only counts once the pointer has
     * travelled [MIN_SELECTION_DRAG_DISTANCE] while the primary button is held. After
     * release the capture waits [SELECTION_SETTLE_DELAY_MS] so the source application
     * has finished updating its own selection before Copy is synthesized.
     */
    private inner class SelectionMouseListener : NativeMouseInputListener {
        private var pressedAt: Point? = null
        private var dragged = false

        override fun nativeMousePressed(event: NativeMouseEvent) {
            if (systemScreenCaptureGuard.onPointerPressed()) {
                // Снимок должен сохранить интерфейс ровно в том виде, в котором пользователь
                // его видит: не закрываем popup и не отправляем Ctrl+C в Snipping Tool.
                abortClipboardInterceptionForScreenCapture()
                pressedAt = null
                dragged = false
                shiftTapGesture.onPointerPressed()
                return
            }

            // Reported before the selection-icon check, not after. This is the only notice the
            // application gets of a press that lands in another program, and the floating popups
            // rely on it to close when the user clicks away. Tying it to the selection button
            // meant turning that button off also stopped popups noticing clicks outside them.
            val awtPointer = currentAwtScreenPoint(event.point)
            onPointerPressed(awtPointer)
            selectionCaptureJob?.cancel()
            shiftTapGesture.onPointerPressed()

            if (event.button != NativeMouseEvent.BUTTON1) return
            if (!shouldInspectMouseSelection()) return
            pressedAt = event.point
            dragged = false
        }

        override fun nativeMouseDragged(event: NativeMouseEvent) {
            if (systemScreenCaptureGuard.onPointerDragged()) {
                pressedAt = null
                dragged = false
                return
            }
            shiftTapGesture.onPointerDragged()
            val start = pressedAt ?: return
            if (start.distance(event.point) >= MIN_SELECTION_DRAG_DISTANCE) dragged = true
        }

        override fun nativeMouseClicked(event: NativeMouseEvent) {
            if (systemScreenCaptureGuard.isSuppressed()) return
            if (event.button != NativeMouseEvent.BUTTON1 || event.clickCount < 2) return
            if (shiftTapTranslateEnabled.get()) {
                shiftTapGesture.onPointerClicked(event.clickCount)
            }
            scheduleSelectionCapture(
                nativePointer = event.point,
                awtPointer = currentAwtScreenPoint(event.point)
            )
        }

        override fun nativeMouseReleased(event: NativeMouseEvent) {
            if (systemScreenCaptureGuard.onPointerReleased()) {
                pressedAt = null
                dragged = false
                abortClipboardInterceptionForScreenCapture()
                return
            }
            val wasSelectionDrag = dragged
            pressedAt = null
            dragged = false
            if (!wasSelectionDrag) return

            if (shiftTapTranslateEnabled.get()) shiftTapGesture.onSelectionCompleted()
            scheduleSelectionCapture(
                nativePointer = event.point,
                awtPointer = currentAwtScreenPoint(event.point)
            )
        }
    }

    private fun scheduleSelectionCapture(nativePointer: Point, awtPointer: Point) {
        if (systemScreenCaptureGuard.isSuppressed()) return
        if (!shouldInspectMouseSelection()) return
        selectionCaptureJob?.cancel()
        selectionCaptureJob = scope.launch {
            val requestId = requestSequence.incrementAndGet()
            val debounce = if (isAutoSelectionActive()) {
                AUTO_SELECTION_DEBOUNCE_MS
            } else {
                SELECTION_SETTLE_DELAY_MS
            }
            delay(debounce)
            if (systemScreenCaptureGuard.isSuppressed()) return@launch

            var selectedText = ""
            var clipboardEvidence: ClipboardSelectionEvidence? = null
            handleSelectedText(
                callback = { selectedText = it },
                onClipboardEvidence = { clipboardEvidence = it },
                preCopyDelayMs = 0,
                requestId = requestId,
                origin = "auto_selection"
            )
            // handleSelectedText уже вышел из clipboard mutex и восстановил пользовательский
            // clipboard. Потенциально медленный UI Automation никогда не продлевает перехват.
            if (selectedText.isBlank()) return@launch
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (systemScreenCaptureGuard.isSuppressed()) return@launch

            // UI Automation ожидает native desktop coordinates, а Swing/JWindow — AWT user
            // coordinates. На HiDPI это разные системы, поэтому точки намеренно не смешиваются.
            val detection = selectionSurfaceDetector.inspect(nativePointer, clipboardEvidence)
            val surface = detection.surface
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (systemScreenCaptureGuard.isSuppressed()) return@launch

            val presentation = resolveSelectionPresentation(
                surface = surface,
                isAutoOverlayEnabled = isAutoSelectionActive()
            )
            logger.info(
                    "Selection presentation: surface=$surface, presentation=$presentation, " +
                    "auto=${isAutoSelectionActive()}, accessibility=${detection.accessibility}, " +
                    "win32=${detection.win32}, clipboard=${detection.clipboard}, " +
                    "accessibilityError=${detection.accessibilityError}, " +
                    "win32Error=${detection.win32Error}"
            )
            deliverSelectionPresentation(
                text = selectedText,
                pointer = awtPointer,
                presentation = presentation,
                onMiniButton = onSelectionDetected,
                onAutoOverlay = { text -> onAutoTranslateSelection(text, requestId) }
            )
        }
    }

    private fun currentAwtScreenPoint(nativePoint: Point): Point =
        resolveAwtScreenPoint(
            nativePoint = nativePoint,
            awtPointerSnapshot = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        )

    private fun isAutoSelectionActive(): Boolean =
        hotkeysEnabled.get() && autoSelectionTranslateEnabled.get()

    /** Обычная комбинация и безопасный modifier-tap запускают один и тот же MVI-сценарий. */
    private fun triggerConfiguredSelectionTranslation() {
        if (!hotkeysEnabled.get() || !shiftTapTranslateEnabled.get()) return
        // Явный hotkey имеет приоритет над отложенным auto-overlay того же выделения.
        selectionCaptureJob?.cancel()
        val requestId = requestSequence.incrementAndGet()
        logger.info("Selection request started: requestId=$requestId, origin=shift")
        onShiftTapStarted(requestId)
        scope.launch {
            handleSelectedText(
                callback = { text -> onShiftTapTranslate(text, requestId) },
                requestId = requestId,
                origin = "shift"
            )
        }
    }

    fun isSystemScreenCaptureSuppressed(): Boolean = systemScreenCaptureGuard.isSuppressed()

    /** При включённом global master capture нужен для любой клетки surface×tray матрицы. */
    private fun shouldInspectMouseSelection(): Boolean = hotkeysEnabled.get()

    private suspend fun handleSelectedText(
        callback: (String) -> Unit,
        onClipboardEvidence: (ClipboardSelectionEvidence?) -> Unit = {},
        preCopyDelayMs: Long = HOTKEY_RELEASE_SETTLE_DELAY_MS,
        requestId: Long = requestSequence.incrementAndGet(),
        origin: String = "hotkey"
    ) {
        val startedAtNanos = System.nanoTime()
        logger.debug("Selection capture started: requestId=$requestId, origin=$origin")
        clipboardInterceptionGuard.track { lease ->
            clipboardMutex.withLock {
                if (!clipboardInterceptionGuard.isCurrent(lease) || systemScreenCaptureGuard.isSuppressed()) {
                    return@withLock
                }

                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val original = runCatching { clipboard.getContents(null) }.getOrNull()
                var ownedClipboardText: String? = null

                try {
                    // Global hotkey callbacks can arrive while Ctrl/Cmd is still physically held.
                    // Give the originating key sequence time to finish before synthesizing Copy.
                    if (preCopyDelayMs > 0) delay(preCopyDelayMs)

                    if (!awaitSafeSyntheticCopyWindow()) {
                        logger.warn(
                            "Selection capture failed: requestId=$requestId, origin=$origin, " +
                                "reason=modifier_held"
                        )
                        callback("")
                        return@withLock
                    }

                    var text: String? = null
                    var clipboardEvidence: ClipboardSelectionEvidence? = null
                    for ((attemptIndex, backoffMs) in longArrayOf(50, 90, 140).withIndex()) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (!clipboardInterceptionGuard.isCurrent(lease) ||
                            systemScreenCaptureGuard.isSuppressed()
                        ) break

                        if (!awaitSafeSyntheticCopyWindow()) {
                            logger.warn(
                                "Selection capture attempt failed: requestId=$requestId, " +
                                    "origin=$origin, attempt=${attemptIndex + 1}, " +
                                    "reason=modifier_held"
                            )
                            break
                        }

                        val sentinel = "qtranslate-copy-${UUID.randomUUID()}"
                        val sentinelWritten = runCatching {
                            clipboard.setContents(StringSelection(sentinel), null)
                        }.isSuccess
                        if (!sentinelWritten) {
                            logger.warn(
                                "Selection capture attempt failed: requestId=$requestId, " +
                                    "origin=$origin, attempt=${attemptIndex + 1}, " +
                                    "reason=clipboard_busy"
                            )
                            delay(backoffMs)
                            continue
                        }
                        ownedClipboardText = sentinel.takeIf { sentinelWritten }

                        if (!simulateCopy()) {
                            logger.warn(
                                "Selection capture attempt failed: requestId=$requestId, " +
                                    "origin=$origin, attempt=${attemptIndex + 1}, " +
                                    "reason=copy_simulation"
                            )
                            break
                        }
                        delay(backoffMs)

                        val candidate = readClipboardText(clipboard)
                        if (!candidate.isNullOrEmpty() && candidate != sentinel) {
                            ownedClipboardText = candidate
                            text = candidate.trim()
                            clipboardEvidence = inspectClipboardSelectionEvidence(
                                runCatching { clipboard.getContents(null) }.getOrNull()
                            )
                            logger.info(
                                "Selection capture completed: requestId=$requestId, origin=$origin, " +
                                    "attempt=${attemptIndex + 1}, length=${text.length}, " +
                                    "latencyMs=${(System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND}"
                            )
                            break
                        } else {
                            logger.debug(
                                "Selection capture attempt empty: requestId=$requestId, " +
                                    "origin=$origin, attempt=${attemptIndex + 1}"
                            )
                        }
                    }

                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (clipboardInterceptionGuard.isCurrent(lease) &&
                        !systemScreenCaptureGuard.isSuppressed()
                    ) {
                        onClipboardEvidence(clipboardEvidence)
                        callback(text.orEmpty())
                        if (text.isNullOrBlank()) {
                            logger.warn(
                                "Selection capture failed: requestId=$requestId, origin=$origin, " +
                                    "reason=empty_selection, attempts=3, " +
                                    "latencyMs=${(System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND}"
                            )
                        }
                    }
                } finally {
                    val expected = ownedClipboardText
                    if (original != null && expected != null) {
                        clipboardInterceptionGuard.restoreIfAllowed(
                            lease = lease,
                            ownsCurrentClipboard = { readClipboardText(clipboard) == expected },
                            restore = { runCatching { clipboard.setContents(original, null) } }
                        )
                    }
                }
            }
        }
    }

    private fun readClipboardText(clipboard: Clipboard): String? = runCatching {
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            clipboard.getData(DataFlavor.stringFlavor).toString()
        } else {
            null
        }
    }.getOrNull()

    private fun abortClipboardInterceptionForScreenCapture() {
        // Сначала инвалидируем право `finally` на restore, и только затем отменяем Job.
        clipboardInterceptionGuard.invalidateForScreenCapture()
        selectionCaptureJob?.cancel()
    }

    /**
     * Ожидает отпускания физических модификаторов и короткого стабильного окна после них.
     * При долгом удержании безопаснее пропустить чтение, чем открыть системное/браузерное меню.
     */
    private suspend fun awaitSafeSyntheticCopyWindow(): Boolean {
        val deadlineNanos = System.nanoTime() + COPY_MODIFIER_RELEASE_TIMEOUT_MS * NANOS_PER_MILLISECOND
        while (syntheticCopyModifierGate.hasPressedModifier()) {
            if (System.nanoTime() >= deadlineNanos) return false
            delay(COPY_MODIFIER_POLL_MS)
        }

        delay(COPY_MODIFIER_QUIET_PERIOD_MS)
        return !syntheticCopyModifierGate.hasPressedModifier()
    }

    private fun simulateCopy(): Boolean {
        if (syntheticCopyModifierGate.hasPressedModifier()) return false

        return runCatching {
            val robot = Robot()
            // Пауза между Ctrl и C расширяла окно гонки с физическим Shift. Очередь Robot
            // сохраняет порядок событий и без неё, а чтение clipboard имеет отдельный backoff.
            robot.autoDelay = 0
            val copyModifier = if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
                KeyEvent.VK_META
            } else {
                KeyEvent.VK_CONTROL
            }
            robot.keyPress(copyModifier)
            robot.keyPress(KeyEvent.VK_C)
            robot.keyRelease(KeyEvent.VK_C)
            robot.keyRelease(copyModifier)
            robot.waitForIdle()
            true
        }.onFailure {
            logger.warn("Copy simulation failed: ${it.message}")
        }.getOrDefault(false)
    }

    private companion object {
        /** Pointer travel, in pixels, before a press-and-drag counts as a selection. */
        const val MIN_SELECTION_DRAG_DISTANCE = 5.0

        /** Grace period after mouse release so the source app can settle its selection. */
        const val SELECTION_SETTLE_DELAY_MS = 80L

        /** Небольшая пауза отличает завершённое выделение от продолжающегося движения мыши. */
        const val AUTO_SELECTION_DEBOUNCE_MS = 220L

        /** Даёт физически отпустить Ctrl/Meta/Shift перед синтезированным Ctrl+C. */
        const val HOTKEY_RELEASE_SETTLE_DELAY_MS = 80L

        /** Максимальное ожидание удерживаемого модификатора перед безопасным отказом. */
        const val COPY_MODIFIER_RELEASE_TIMEOUT_MS = 650L

        /** Модификатор должен оставаться отпущенным до начала синтетического Ctrl+C. */
        const val COPY_MODIFIER_QUIET_PERIOD_MS = 45L

        const val COPY_MODIFIER_POLL_MS = 8L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
