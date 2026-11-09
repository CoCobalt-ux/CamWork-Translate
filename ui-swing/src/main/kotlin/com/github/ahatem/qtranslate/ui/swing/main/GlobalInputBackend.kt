package com.github.ahatem.qtranslate.ui.swing.main

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import com.github.kwhat.jnativehook.mouse.NativeMouseInputListener
import com.tulskiy.keymaster.common.Provider
import javax.swing.KeyStroke

/** Горячие клавиши и мышиный hook восстанавливаются независимо друг от друга. */
internal interface GlobalInputBackend {
    fun initializeHotkeys()
    fun resetHotkeys()
    fun registerHotkey(keyStroke: KeyStroke, onPressed: () -> Unit)
    fun shutdownHotkeys()
    fun isNativeHookRegistered(): Boolean
    fun ensureNativeHook(keyboard: NativeKeyListener, mouse: NativeMouseInputListener)
    fun shutdownNativeHook(keyboard: NativeKeyListener, mouse: NativeMouseInputListener)
}

internal class SystemGlobalInputBackend : GlobalInputBackend {
    private var provider: Provider? = null
    private var keyboard: NativeKeyListener? = null
    private var mouse: NativeMouseInputListener? = null
    private var ownsNativeHook = false

    override fun initializeHotkeys() {
        if (provider == null) {
            provider = checkNotNull(Provider.getCurrentProvider(false)) { "Сервис горячих клавиш недоступен" }
        }
    }

    override fun resetHotkeys() {
        checkNotNull(provider).reset()
    }

    override fun registerHotkey(keyStroke: KeyStroke, onPressed: () -> Unit) {
        checkNotNull(provider).register(keyStroke) { onPressed() }
    }

    override fun shutdownHotkeys() {
        val current = provider
        provider = null
        try {
            current?.reset()
        } finally {
            current?.stop()
        }
    }

    override fun isNativeHookRegistered(): Boolean = GlobalScreen.isNativeHookRegistered()

    override fun ensureNativeHook(keyboard: NativeKeyListener, mouse: NativeMouseInputListener) {
        val registrationLost = !GlobalScreen.isNativeHookRegistered()
        if (registrationLost) {
            GlobalScreen.registerNativeHook()
            ownsNativeHook = true
        }
        if (!registrationLost && this.keyboard === keyboard && this.mouse === mouse) return
        detachListeners()
        try {
            GlobalScreen.addNativeKeyListener(keyboard)
            this.keyboard = keyboard
            GlobalScreen.addNativeMouseListener(mouse)
            this.mouse = mouse
            GlobalScreen.addNativeMouseMotionListener(mouse)
        } catch (error: Exception) {
            detachListeners()
            throw error
        }
    }

    override fun shutdownNativeHook(keyboard: NativeKeyListener, mouse: NativeMouseInputListener) {
        try {
            detachListeners()
        } finally {
            if (ownsNativeHook && GlobalScreen.isNativeHookRegistered()) GlobalScreen.unregisterNativeHook()
            ownsNativeHook = false
        }
    }

    private fun detachListeners() {
        keyboard?.let(GlobalScreen::removeNativeKeyListener)
        mouse?.let {
            GlobalScreen.removeNativeMouseListener(it)
            GlobalScreen.removeNativeMouseMotionListener(it)
        }
        keyboard = null
        mouse = null
    }
}
