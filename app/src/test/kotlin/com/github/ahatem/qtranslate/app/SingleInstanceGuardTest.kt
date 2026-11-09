package com.github.ahatem.qtranslate.app

import java.io.File
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SingleInstanceGuardTest {
    @Test
    fun `барьер из двадцати четырёх JVM допускает только одного владельца`() = temporaryDirectory { root ->
        val directory = root.resolve("owner")
        val control = Files.createDirectories(root.resolve("control"))
        val processes = mutableListOf<Fork>()
        try {
            repeat(24) { index -> processes += startFork("barrier", directory, control, index.toString()) }
            awaitCondition(90, { diagnostics(processes) }) {
                checkNoFailedForks(processes)
                processes.indices.all { Files.exists(control.resolve("ready-$it")) }
            }
            Files.writeString(control.resolve("go"), "Начать одновременно")
            awaitCondition(45, { diagnostics(processes) }) {
                checkNoFailedForks(processes)
                processes.indices.all { Files.exists(control.resolve("result-$it.txt")) }
            }
            val results = processes.indices.map { Files.readString(control.resolve("result-$it.txt")) }
            assertEquals(1, results.count { it.startsWith("ACQUIRED ") }, results.joinToString("\n"))
            assertEquals(23, results.count { it.startsWith("ALREADY_RUNNING ") }, results.joinToString("\n"))
            Files.writeString(control.resolve("release"), "Завершить владельца")
            processes.forEach { fork ->
                assertTrue(fork.process.waitFor(20, TimeUnit.SECONDS), diagnostics(processes))
                assertEquals(0, fork.process.exitValue(), diagnostics(processes))
            }
        } finally {
            finishForks(control, processes)
        }
    }

    @Test
    fun `аварийно завершённый владелец не мешает запуску со старым endpoint`() = temporaryDirectory { root ->
        val directory = root.resolve("owner")
        val control = Files.createDirectories(root.resolve("control"))
        val processes = mutableListOf<Fork>()
        try {
            val owner = startFork("hold", directory, control, "owner")
            processes += owner
            awaitCondition(45, { diagnostics(processes) }) {
                checkNoFailedForks(processes)
                Files.exists(control.resolve("result-owner.txt"))
            }
            assertEquals("ACQUIRED true", Files.readString(control.resolve("result-owner.txt")))
            val endpoint = directory.resolve("owner.endpoint")
            val staleEndpoint = Files.readString(endpoint)

            owner.process.destroyForcibly()
            assertTrue(owner.process.waitFor(15, TimeUnit.SECONDS), "Не удалось завершить тестовую JVM владельца")
            assertTrue(Files.exists(endpoint), "Аварийное завершение должно оставить старый endpoint для проверки восстановления")

            SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 150).use { replacement ->
                val result = assertIs<InstanceStartResult.Acquired>(replacement.acquire {})
                assertTrue(result.focusAvailable)
                assertNotEquals(staleEndpoint, Files.readString(endpoint), "Новый владелец должен публиковать новый endpoint/nonce")
            }
        } finally {
            finishForks(control, processes)
        }
    }

    @Test
    fun `двадцать четыре потока одной JVM не обходят блокировку файла`() = temporaryDirectory { directory ->
        val ready = CountDownLatch(24)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(24)
        val guards = List(24) { SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 200) }
        val control = Files.createDirectories(directory.resolve("external-probe"))
        val probes = mutableListOf<Fork>()
        try {
            val attempts = guards.map { guard ->
                executor.submit(Callable {
                    ready.countDown()
                    check(start.await(15, TimeUnit.SECONDS)) { "Барьер потоков не открыт" }
                    guard.acquire {}
                })
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS), "Не все потоки достигли барьера")
            start.countDown()
            val results = attempts.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is InstanceStartResult.Acquired })
            assertEquals(23, results.count { it is InstanceStartResult.AlreadyRunning }, results.joinToString())
            // Проверяем и ОС: закрытие каналов проигравших не должно снимать POSIX-блокировку владельца.
            probes += startFork("hold", directory, control, "probe")
            awaitCondition(45, { diagnostics(probes) }) {
                checkNoFailedForks(probes)
                Files.exists(control.resolve("result-probe.txt"))
            }
            assertTrue(Files.readString(control.resolve("result-probe.txt")).startsWith("ALREADY_RUNNING "))
        } finally {
            start.countDown()
            finishForks(control, probes)
            guards.forEach(SingleInstanceGuard::close)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Не завершены тестовые потоки")
        }
    }

    @Test
    fun `повторное закрытие безопасно а файл блокировки сохраняет идентичность`() = temporaryDirectory { directory ->
        val owner = SingleInstanceGuard(directory, legacyPort = null)
        try {
            assertIs<InstanceStartResult.Acquired>(owner.acquire {})
            val lock = directory.resolve("owner.lock")
            val identity = Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey()
            owner.close()
            owner.close()
            assertTrue(Files.exists(lock), "Файл блокировки нельзя удалять при освобождении")
            if (identity != null) {
                assertEquals(identity, Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey())
            }
            SingleInstanceGuard(directory, legacyPort = null).use { next ->
                assertIs<InstanceStartResult.Acquired>(next.acquire {})
            }
        } finally {
            owner.close()
        }
    }

    @Test
    fun `удерживаемая OS блокировка без endpoint запрещает второй запуск`() = temporaryDirectory { directory ->
        holdOperatingSystemLock(directory) {
            Files.delete(directory.resolve("owner.endpoint"))
            SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 100).use { contender ->
                val result = assertIs<InstanceStartResult.AlreadyRunning>(contender.acquire {})
                assertFalse(result.focusRequested)
            }
        }
    }

    @Test
    fun `битый endpoint не разрешает запуск поверх OS блокировки`() = temporaryDirectory { directory ->
        holdOperatingSystemLock(directory) {
            val endpoint = directory.resolve("owner.endpoint")
            Files.writeString(endpoint, "Повреждённый endpoint без порта и nonce")
            SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 100).use { contender ->
                val result = assertIs<InstanceStartResult.AlreadyRunning>(contender.acquire {})
                assertFalse(result.focusRequested)
            }
            assertEquals("Повреждённый endpoint без порта и nonce", Files.readString(endpoint))
        }
    }

    @Test
    fun `недоступный IPC в корректном endpoint не разрешает второй запуск`() = temporaryDirectory { directory ->
        holdOperatingSystemLock(directory) {
            val unusedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            Files.writeString(directory.resolve("owner.endpoint"), "$unusedPort\n${UUID.randomUUID()}\n")
            SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 100).use { contender ->
                val result = assertIs<InstanceStartResult.AlreadyRunning>(contender.acquire {})
                assertFalse(result.focusRequested)
            }
        }
    }

    @Test
    fun `таймаут молчащего IPC не обходит удерживаемую OS блокировку`() = temporaryDirectory { directory ->
        val requestRead = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        LoopbackServer { client ->
            val signal = client.getInputStream().bufferedReader().readLine()
            if (signal?.startsWith("FOCUS ") == true) requestRead.countDown()
            check(releaseServer.await(10, TimeUnit.SECONDS)) { "Тестовый молчащий сервер не освобождён" }
        }.use { silent ->
            try {
                holdOperatingSystemLock(directory) {
                    Files.writeString(directory.resolve("owner.endpoint"), "${silent.port}\n${UUID.randomUUID()}\n")
                    SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 100).use { contender ->
                        val result = assertIs<InstanceStartResult.AlreadyRunning>(contender.acquire {})
                        assertFalse(result.focusRequested)
                        assertTrue(requestRead.await(3, TimeUnit.SECONDS), "Должно произойти настоящее подключение и чтение FOCUS")
                    }
                }
            } finally {
                releaseServer.countDown()
            }
        }
    }

    @Test
    fun `неверный nonce не даёт подтверждения фокуса и не создаёт второго владельца`() = temporaryDirectory { directory ->
        holdOperatingSystemLock(directory) {
            val endpoint = directory.resolve("owner.endpoint")
            val port = Files.readAllLines(endpoint).first().toInt()
            Files.writeString(endpoint, "$port\n${UUID.randomUUID()}\n")
            SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 150).use { contender ->
                val result = assertIs<InstanceStartResult.AlreadyRunning>(contender.acquire {})
                assertFalse(result.focusRequested)
            }
        }
    }

    @Test
    fun `немой TCP клиент закрывается по deadline и не блокирует следующий запрос фокуса`() = temporaryDirectory { directory ->
        val focused = CountDownLatch(1)
        SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 120).use { owner ->
            assertTrue(assertIs<InstanceStartResult.Acquired>(owner.acquire { focused.countDown() }).focusAvailable)
            val port = Files.readAllLines(directory.resolve("owner.endpoint")).first().toInt()
            Socket().use { silent ->
                silent.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1_000)
                silent.soTimeout = 5_000
                // Клиент ничего не посылает и остаётся открытым: закрыть соединение обязан сервер.
                assertEquals(-1, silent.getInputStream().read(), "Немой клиент должен отключаться по серверному deadline")
                SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 300).use { contender ->
                    val result = assertIs<InstanceStartResult.AlreadyRunning>(contender.acquire {})
                    assertTrue(result.focusRequested)
                    assertTrue(focused.await(5, TimeUnit.SECONDS), "Следующий запрос должен показать окно владельца")
                }
            }
        }
    }

    @Test
    fun `отказ публикации endpoint сохраняет OS блокировку без сетевого fallback`() = temporaryDirectory { directory ->
        Files.createDirectory(directory.resolve("owner.endpoint"))
        val control = Files.createDirectories(directory.resolve("focus-unavailable-probe"))
        val probes = mutableListOf<Fork>()
        SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 150).use { owner ->
            val result = assertIs<InstanceStartResult.Acquired>(owner.acquire {})
            assertFalse(result.focusAvailable)
            try {
                probes += startFork("hold", directory, control, "probe")
                awaitCondition(45, { diagnostics(probes) }) {
                    checkNoFailedForks(probes)
                    Files.exists(control.resolve("result-probe.txt"))
                }
                assertEquals("ALREADY_RUNNING false", Files.readString(control.resolve("result-probe.txt")))
            } finally {
                finishForks(control, probes)
            }
        }
    }

    @Test
    fun `посторонний процесс на случайном legacy порту не мешает первому запуску`() = temporaryDirectory { directory ->
        LoopbackServer { client ->
            client.getInputStream().bufferedReader().readLine()
            PrintWriter(client.getOutputStream(), true).println("ПОСТОРОННИЙ СЕРВИС")
        }.use { foreign ->
            SingleInstanceGuard(directory, legacyPort = foreign.port, ipcTimeoutMillis = 200).use { guard ->
                assertIs<InstanceStartResult.Acquired>(guard.acquire {})
            }
        }
    }

    @Test
    fun `разные каталоги с одним legacy портом получают отдельных современных владельцев`() = temporaryDirectory { root ->
        val legacyPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val firstDirectory = root.resolve("first")
        val secondDirectory = root.resolve("second")
        val firstFocusCalls = AtomicInteger()
        SingleInstanceGuard(firstDirectory, legacyPort = legacyPort, ipcTimeoutMillis = 200).use { first ->
            assertTrue(assertIs<InstanceStartResult.Acquired>(first.acquire { firstFocusCalls.incrementAndGet() }).focusAvailable)
            assertEquals(legacyPort, Files.readAllLines(firstDirectory.resolve("owner.endpoint")).first().toInt())
            SingleInstanceGuard(secondDirectory, legacyPort = legacyPort, ipcTimeoutMillis = 200).use { second ->
                assertTrue(assertIs<InstanceStartResult.Acquired>(second.acquire {}).focusAvailable)
                val secondPort = Files.readAllLines(secondDirectory.resolve("owner.endpoint")).first().toInt()
                assertNotEquals(legacyPort, secondPort)
                assertEquals(0, firstFocusCalls.get(), "PROTOCOL-проба не должна показывать окно другого каталога")
            }
        }
    }

    @Test
    fun `исключение focus callback не останавливает следующий запрос`() = temporaryDirectory { directory ->
        val callbacks = AtomicInteger()
        val focusedTwice = CountDownLatch(2)
        SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 200).use { owner ->
            assertIs<InstanceStartResult.Acquired>(owner.acquire {
                val call = callbacks.incrementAndGet()
                focusedTwice.countDown()
                if (call == 1) throw IllegalStateException("Управляемый отказ показа окна")
            })
            repeat(2) {
                SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 300).use { contender ->
                    assertTrue(assertIs<InstanceStartResult.AlreadyRunning>(contender.acquire {}).focusRequested)
                }
            }
            assertTrue(focusedTwice.await(5, TimeUnit.SECONDS), "Приём запросов должен пережить исключение обработчика")
            assertEquals(2, callbacks.get())
        }
    }

    @Test
    fun `старый клиент FOCUS получает прежний ACK без nonce`() = temporaryDirectory { directory ->
        val focused = CountDownLatch(1)
        SingleInstanceGuard(directory, legacyPort = null, ipcTimeoutMillis = 200).use { owner ->
            assertIs<InstanceStartResult.Acquired>(owner.acquire { focused.countDown() })
            val port = Files.readAllLines(directory.resolve("owner.endpoint")).first().toInt()
            Socket().use { legacyClient ->
                legacyClient.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1_000)
                legacyClient.soTimeout = 3_000
                PrintWriter(legacyClient.getOutputStream(), true).println("FOCUS")
                assertEquals("CAMWORK-TRANSLATE", legacyClient.getInputStream().bufferedReader().readLine())
            }
            assertTrue(focused.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `известный старый ACK передаёт фокус действующему старому экземпляру`() = temporaryDirectory { directory ->
        val requested = CountDownLatch(1)
        LoopbackServer { client ->
            val signal = client.getInputStream().bufferedReader().readLine()
            // Старая версия закрывает неизвестный PROTOCOL; следующий клиент присылает FOCUS.
            if (signal == "FOCUS") {
                requested.countDown()
                PrintWriter(client.getOutputStream(), true).println("CAMWORK-TRANSLATE")
            }
        }.use { legacy ->
            SingleInstanceGuard(directory, legacyPort = legacy.port, ipcTimeoutMillis = 300).use { guard ->
                val result = assertIs<InstanceStartResult.AlreadyRunning>(guard.acquire {})
                assertTrue(result.focusRequested)
                assertTrue(requested.await(3, TimeUnit.SECONDS))
                assertEquals(2, legacy.connections.get(), "Ожидаются PROTOCOL-проба и отдельное legacy-подключение FOCUS")
            }
        }
    }

    @Test
    fun `ошибка каталога возвращает Failed и не маскируется legacy fallback`() = temporaryDirectory { root ->
        val invalidDirectory = root.resolve("regular-file")
        Files.writeString(invalidDirectory, "Это файл а не каталог")
        LoopbackServer { client ->
            client.getInputStream().bufferedReader().readLine()
            PrintWriter(client.getOutputStream(), true).println("CAMWORK-TRANSLATE")
        }.use { legacy ->
            SingleInstanceGuard(invalidDirectory, legacyPort = legacy.port, ipcTimeoutMillis = 150).use { guard ->
                assertIs<InstanceStartResult.Failed>(guard.acquire {})
            }
            assertEquals(0, legacy.connections.get(), "Ошибка файловой блокировки должна завершать acquire без сетевого обхода")
        }
    }

    private class LoopbackServer(private val handle: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val connections = AtomicInteger()
        private val clients = CopyOnWriteArrayList<Socket>()
        private val listener = thread(isDaemon = true, name = "Тестовый legacy IPC") {
            while (!server.isClosed) {
                try {
                    server.accept().use { client ->
                        clients += client
                        try {
                            connections.incrementAndGet()
                            client.soTimeout = 1_000
                            handle(client)
                        } finally {
                            clients -= client
                        }
                    }
                } catch (_: java.io.IOException) {
                    if (server.isClosed) break
                }
            }
        }

        override fun close() {
            server.close()
            clients.forEach { runCatching { it.close() } }
            listener.join(3_000)
            assertFalse(listener.isAlive, "Тестовый IPC сервер не остановился")
        }
    }

    private data class Fork(val process: Process, val output: Path)

    private fun startFork(mode: String, directory: Path, control: Path, id: String): Fork {
        val executable = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", executable)
        val classpath = listOf(
            SingleInstanceGuard::class.java,
            SingleInstanceProcessMain::class.java,
            Unit::class.java
        ).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(File.pathSeparator)
        val output = control.resolve("jvm-$id.log")
        val process = ProcessBuilder(
            java.toString(), "-Xmx48m", "-Xss256k", "-XX:ActiveProcessorCount=2", "-cp", classpath,
            SingleInstanceProcessMain::class.java.name, mode, directory.toString(), control.toString(), id
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        return Fork(process, output)
    }

    private fun finishForks(control: Path, processes: List<Fork>) {
        Files.writeString(control.resolve("go"), "Открыть оставшиеся барьеры")
        Files.writeString(control.resolve("release"), "Освободить владельца")
        processes.forEach { fork ->
            if (fork.process.isAlive && !fork.process.waitFor(3, TimeUnit.SECONDS)) {
                fork.process.destroyForcibly()
                assertTrue(fork.process.waitFor(10, TimeUnit.SECONDS), "Осталась тестовая JVM: ${fork.process.pid()}")
            }
        }
    }

    private fun diagnostics(processes: List<Fork>): String = processes.joinToString("\n") { fork ->
        val status = if (fork.process.isAlive) "работает" else "код ${fork.process.exitValue()}"
        val output = if (Files.exists(fork.output)) {
            Files.readAllBytes(fork.output).toString(Charsets.UTF_8).takeLast(4_000)
        } else "Журнал отсутствует"
        "JVM ${fork.process.pid()} $status: $output"
    }

    private fun checkNoFailedForks(processes: List<Fork>) {
        val failed = processes.filter { !it.process.isAlive && it.process.exitValue() != 0 }
        check(failed.isEmpty()) { "Тестовая JVM завершилась до прохождения барьера: ${diagnostics(failed)}" }
    }

    private fun awaitCondition(seconds: Long, diagnostic: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Истёк срок ожидания: ${diagnostic()}" }
            Thread.sleep(20L)
        }
    }

    private fun holdOperatingSystemLock(directory: Path, action: () -> Unit) {
        val control = Files.createDirectories(directory.resolve("lock-owner-control"))
        val processes = mutableListOf<Fork>()
        try {
            processes += startFork("hold", directory, control, "lock-owner")
            awaitCondition(45, { diagnostics(processes) }) {
                checkNoFailedForks(processes)
                Files.exists(control.resolve("result-lock-owner.txt"))
            }
            assertEquals("ACQUIRED true", Files.readString(control.resolve("result-lock-owner.txt")))
            action()
            assertTrue(processes.single().process.isAlive, "Владелец OS-блокировки должен оставаться запущенным")
        } finally {
            finishForks(control, processes)
        }
    }

    private fun temporaryDirectory(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("camwork-single-instance-test-")
        try {
            action(root)
        } finally {
            val allowedTemporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath().normalize()
            val resolvedRoot = root.toRealPath().normalize()
            check(resolvedRoot.startsWith(allowedTemporary) &&
                resolvedRoot.fileName.toString().startsWith("camwork-single-instance-test-")) {
                "Отказ от рекурсивной очистки неожиданного пути $resolvedRoot"
            }
            assertTrue(resolvedRoot.toFile().deleteRecursively(), "Не удалён собственный временный каталог теста $resolvedRoot")
        }
    }
}
