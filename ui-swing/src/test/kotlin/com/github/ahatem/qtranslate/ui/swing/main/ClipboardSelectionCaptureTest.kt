package com.github.ahatem.qtranslate.ui.swing.main

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardSelectionCaptureTest {
    @Test
    fun `медленное копирование захватывается одной командой с первой попытки`() = runTest {
        val clipboard = TestClipboard()
        clipboard.write("старый буфер")
        var copies = 0
        val capture = ClipboardSelectionCapture(clipboard, simulateCopy = {
            copies++
            backgroundScope.launch {
                delay(210)
                clipboard.write("выделение")
            }
            true
        }, generation = { clipboard.generation })

        val captured = assertIs<SelectionCaptureResult.Captured>(capture.read())
        assertEquals("выделение", captured.snapshot.text)
        assertEquals("старый буфер", clipboard.text())
        assertEquals(1, copies)
        assertEquals(220L, testScheduler.currentTime)
    }

    @Test
    fun `поколение подтверждает копирование текста совпадающего с буфером`() = runTest {
        val clipboard = TestClipboard()
        clipboard.write("тот же текст")
        val capture = ClipboardSelectionCapture(clipboard, simulateCopy = {
            clipboard.write("тот же текст")
            true
        }, generation = { clipboard.generation })

        val captured = assertIs<SelectionCaptureResult.Captured>(capture.read())
        assertEquals("тот же текст", captured.snapshot.text)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `несколько обновлений не дают перезаписать новое копирование пользователя`() = runTest {
        val clipboard = TestClipboard()
        clipboard.write("старый буфер")
        val capture = ClipboardSelectionCapture(clipboard, simulateCopy = {
            clipboard.write("выделение")
            clipboard.write("новый буфер пользователя")
            true
        }, generation = { clipboard.generation })

        capture.read()
        assertEquals("новый буфер пользователя", clipboard.text())
    }

    @Test
    fun `отмена восстанавливает временную метку на платформе без счётчика`() = runTest {
        val clipboard = TestClipboard()
        clipboard.write("буфер пользователя")
        val capture = ClipboardSelectionCapture(clipboard, simulateCopy = { true }, generation = { null })

        val job = launch { capture.read() }
        runCurrent()
        job.cancelAndJoin()

        assertEquals("буфер пользователя", clipboard.text())
    }

    @Test
    fun `следующее выделение работает после ста циклов отказа и восстановления`() = runTest {
        val clipboard = TestClipboard()
        clipboard.write("буфер пользователя")
        var shouldCopy = false
        val guard = ClipboardInterceptionGuard()
        val capture = ClipboardSelectionCapture(clipboard, simulateCopy = {
            if (shouldCopy) clipboard.write("выделение")
            true
        }, generation = { clipboard.generation }, timeoutMillis = 100)

        repeat(100) {
            shouldCopy = false
            val failed = assertIs<SelectionCaptureResult.Failed>(capture.read(guard))
            assertEquals(SelectionCaptureFailure.COPY_UNCONFIRMED, failed.reason)
            assertEquals("буфер пользователя", clipboard.text())

            shouldCopy = true
            val captured = assertIs<SelectionCaptureResult.Captured>(capture.read(guard))
            assertEquals("выделение", captured.snapshot.text)
            assertEquals("буфер пользователя", clipboard.text())
        }
    }

    private suspend fun ClipboardSelectionCapture.read(
        guard: ClipboardInterceptionGuard = ClipboardInterceptionGuard()
    ): SelectionCaptureResult? = guard.track { lease -> capture(guard, lease) }

    private class TestClipboard : Clipboard("буфер для проверки") {
        var generation = 1L
            private set

        override fun setContents(contents: Transferable, owner: ClipboardOwner?) {
            super.setContents(contents, owner)
            generation++
        }

        fun write(text: String) = setContents(StringSelection(text), null)
        fun text(): String = getData(DataFlavor.stringFlavor) as String
    }
}
