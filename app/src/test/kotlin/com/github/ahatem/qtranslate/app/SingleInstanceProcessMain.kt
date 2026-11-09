package com.github.ahatem.qtranslate.app

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** Отдельная JVM использует только guard, Java и стандартную библиотеку Kotlin. */
internal object SingleInstanceProcessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "Ожидаются режим, каталог guard, каталог барьера и идентификатор" }
        val mode = args[0]
        val directory = Path.of(args[1])
        val control = Path.of(args[2])
        val id = args[3]
        Files.createDirectories(control)
        Files.writeString(control.resolve("ready-$id"), "Готов к запуску")
        try {
            if (mode == "barrier") awaitFile(control.resolve("go"))
            SingleInstanceGuard(directory = directory, legacyPort = null, ipcTimeoutMillis = 250).use { guard ->
                val result = guard.acquire(onFocusRequested = {})
                val value = when (result) {
                    is InstanceStartResult.Acquired -> "ACQUIRED ${result.focusAvailable}"
                    is InstanceStartResult.AlreadyRunning -> "ALREADY_RUNNING ${result.focusRequested}"
                    is InstanceStartResult.Failed -> "FAILED ${result.error.javaClass.name}: ${result.error.message}"
                }
                publishResult(control, id, value)
                println(value)
                if (result is InstanceStartResult.Acquired) awaitFile(control.resolve("release"))
            }
        } catch (error: Throwable) {
            publishResult(control, id, "FAILED ${error.javaClass.name}: ${error.message}")
            error.printStackTrace(System.err)
            throw error
        }
    }

    private fun publishResult(control: Path, id: String, text: String) {
        val temporary = control.resolve("result-$id.part")
        val result = control.resolve("result-$id.txt")
        Files.writeString(temporary, text)
        try {
            Files.move(temporary, result, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, result, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun awaitFile(file: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (!Files.exists(file)) {
            check(System.nanoTime() < deadline) { "Истёк срок ожидания тестового сигнала $file" }
            Thread.sleep(10L)
        }
    }
}
