package com.github.ahatem.qtranslate.ui.swing.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardInterceptionGuardTest {

    @Test
    fun `Print Screen отменяет уже выполняющийся захват и запрещает поздний restore`() = runBlocking {
        val guard = ClipboardInterceptionGuard()
        val started = CompletableDeferred<Unit>()
        var restored = false
        var restoreAllowed = true

        val captureJob = launch {
            guard.track { lease ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    restoreAllowed = guard.restoreIfAllowed(
                        lease,
                        ownsCurrentClipboard = { true },
                        restore = { restored = true }
                    )
                }
            }
        }

        started.await()
        guard.invalidateForScreenCapture()
        captureJob.join()

        assertTrue(captureJob.isCancelled)
        assertFalse(restoreAllowed)
        assertFalse(restored)
    }

    @Test
    fun `операция ожидающая clipboardMutex тоже отменяется до Ctrl C`() = runBlocking {
        val guard = ClipboardInterceptionGuard()
        val clipboardMutex = Mutex(locked = true)
        val waiting = CompletableDeferred<Unit>()
        var enteredClipboardSection = false

        val queuedJob = launch {
            guard.track {
                waiting.complete(Unit)
                clipboardMutex.withLock { enteredClipboardSection = true }
            }
        }

        waiting.await()
        guard.invalidateForScreenCapture()
        clipboardMutex.unlock()
        queuedJob.join()

        assertTrue(queuedJob.isCancelled)
        assertFalse(enteredClipboardSection)
    }

    @Test
    fun `внешнее изменение clipboard запрещает restore без Print Screen`() = runBlocking {
        val guard = ClipboardInterceptionGuard()
        var restored = false

        guard.track { lease ->
            val allowed = guard.restoreIfAllowed(
                lease,
                ownsCurrentClipboard = { false },
                restore = { restored = true }
            )
            assertFalse(allowed)
        }

        assertFalse(restored)
    }

    @Test
    fun `неизменённый clipboard восстанавливается обычным путём`() = runBlocking {
        val guard = ClipboardInterceptionGuard()
        var restored = false

        guard.track { lease ->
            val allowed = guard.restoreIfAllowed(
                lease,
                ownsCurrentClipboard = { true },
                restore = { restored = true }
            )
            assertTrue(allowed)
        }

        assertTrue(restored)
    }
}
