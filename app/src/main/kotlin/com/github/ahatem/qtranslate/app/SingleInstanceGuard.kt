package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.core.shared.AppConstants
import java.io.IOException
import java.io.PrintWriter
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal sealed interface InstanceStartResult {
    data class Acquired(val focusAvailable: Boolean) : InstanceStartResult
    data class AlreadyRunning(val focusRequested: Boolean) : InstanceStartResult
    data class Failed(val error: Exception) : InstanceStartResult
}

/** Право запуска определяется блокировкой ОС; недоступный IPC никогда его не предоставляет. */
internal class SingleInstanceGuard(
    private val directory: Path = defaultInstanceDirectory(),
    private val legacyPort: Int? = 28731,
    private val ipcTimeoutMillis: Int = 500
) : AutoCloseable {
    private var lockPath: Path? = null
    private var channel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var server: ServerSocket? = null
    private var publishedEndpoint: Endpoint? = null
    private val activeClient = AtomicReference<Socket?>()

    init {
        require(ipcTimeoutMillis in 1..60_000)
        require(legacyPort == null || legacyPort in 1..65535)
    }

    @Synchronized
    fun acquire(onFocusRequested: () -> Unit): InstanceStartResult {
        if (fileLock?.isValid == true) {
            return InstanceStartResult.AlreadyRunning(requestFocus(directory))
        }
        try {
            val instanceDirectory = Files.createDirectories(directory).toRealPath()
            val ownerPath = instanceDirectory.resolve(LOCK_FILE)
            // На POSIX закрытие второго канала того же файла может снять блокировку первого.
            // Поэтому внутри JVM допускается только один канал для каждого пути владельца.
            if (owners.putIfAbsent(ownerPath, this) != null) {
                return InstanceStartResult.AlreadyRunning(requestFocus(instanceDirectory))
            }
            lockPath = ownerPath
            channel = FileChannel.open(ownerPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            fileLock = try {
                channel!!.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (fileLock == null) {
                close()
                return InstanceStartResult.AlreadyRunning(requestFocus(instanceDirectory))
            }

            val socket = try {
                openFocusSocket()
            } catch (error: Exception) {
                // Блокировка уже удерживается: запрет второго запуска действует и без фокуса.
                System.err.println("CamWork Translate: показ окна через IPC недоступен (${error.javaClass.simpleName}).")
                return InstanceStartResult.Acquired(focusAvailable = false)
            }
            if (socket == null) {
                // Старая версия не знает файловую блокировку, но ответила своим протоколом.
                close()
                return InstanceStartResult.AlreadyRunning(focusRequested = true)
            }
            server = socket
            val endpoint = Endpoint(socket.localPort, UUID.randomUUID().toString())
            try {
                publishEndpoint(instanceDirectory, endpoint)
                publishedEndpoint = endpoint
                Thread({ listen(socket, endpoint, onFocusRequested) }, "camwork-instance-focus")
                    .apply { isDaemon = true }
                    .start()
            } catch (error: Exception) {
                socket.close()
                server = null
                System.err.println("CamWork Translate: показ окна через IPC недоступен (${error.javaClass.simpleName}).")
                return InstanceStartResult.Acquired(focusAvailable = false)
            }
            return InstanceStartResult.Acquired(focusAvailable = true)
        } catch (error: Exception) {
            close()
            return InstanceStartResult.Failed(error)
        }
    }

    /** Файл владельца не удаляется: его inode должен сохраняться между запусками. */
    @Synchronized
    override fun close() {
        runCatching { server?.close() }
        server = null
        runCatching { activeClient.getAndSet(null)?.close() }
        val ownerPath = lockPath
        if (fileLock?.isValid == true && publishedEndpoint != null && ownerPath != null) {
            val endpointPath = ownerPath.parent.resolve(ENDPOINT_FILE)
            runCatching {
                if (readEndpoint(endpointPath) == publishedEndpoint) Files.deleteIfExists(endpointPath)
            }
        }
        publishedEndpoint = null
        runCatching { fileLock?.release() }
        fileLock = null
        runCatching { channel?.close() }
        channel = null
        if (ownerPath != null) owners.remove(ownerPath, this)
        lockPath = null
    }

    private fun openFocusSocket(): ServerSocket? {
        if (legacyPort != null) {
            try {
                return bind(legacyPort)
            } catch (_: BindException) {
                val modernOwner = exchange(legacyPort, PROTOCOL_SIGNAL, PROTOCOL_ACKNOWLEDGEMENT)
                if (!modernOwner && exchange(legacyPort, FOCUS_SIGNAL, ACKNOWLEDGEMENT)) return null
            }
        }
        return bind(0)
    }

    private fun bind(port: Int): ServerSocket = ServerSocket().apply {
        try {
            bind(InetSocketAddress(LOOPBACK, port), 128)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun listen(socket: ServerSocket, endpoint: Endpoint, onFocusRequested: () -> Unit) {
        while (!socket.isClosed) {
            try {
                socket.accept().use { client ->
                    activeClient.set(client)
                    try {
                        if (socket.isClosed) return@use
                        val signal = readLine(client, ipcTimeoutMillis)
                        if (signal == PROTOCOL_SIGNAL) {
                            PrintWriter(client.getOutputStream(), true).println(PROTOCOL_ACKNOWLEDGEMENT)
                            return@use
                        }
                        val acknowledgement = when (signal) {
                            "$FOCUS_SIGNAL ${endpoint.token}" -> "$ACKNOWLEDGEMENT ${endpoint.token}"
                            FOCUS_SIGNAL -> ACKNOWLEDGEMENT
                            else -> return@use
                        }
                        if (socket.isClosed) return@use
                        // Ответ не зависит от доступности EDT и ошибки обработчика окна.
                        PrintWriter(client.getOutputStream(), true).println(acknowledgement)
                        runCatching(onFocusRequested)
                    } finally {
                        activeClient.compareAndSet(client, null)
                    }
                }
            } catch (_: IOException) {
                // Обрыв или таймаут одного клиента не останавливает приём следующих.
            }
        }
    }

    private fun requestFocus(instanceDirectory: Path): Boolean = runCatching {
        val endpoint = readEndpoint(instanceDirectory.resolve(ENDPOINT_FILE)) ?: return false
        exchange(endpoint.port, "$FOCUS_SIGNAL ${endpoint.token}", "$ACKNOWLEDGEMENT ${endpoint.token}")
    }.getOrDefault(false)

    private fun exchange(port: Int, signal: String, expectedReply: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), ipcTimeoutMillis)
            PrintWriter(socket.getOutputStream(), true).println(signal)
            readLine(socket, ipcTimeoutMillis * 3) == expectedReply
        }
    }.getOrDefault(false)

    private fun publishEndpoint(instanceDirectory: Path, endpoint: Endpoint) {
        val temporary = Files.createTempFile(instanceDirectory, "owner-", ".tmp")
        try {
            Files.writeString(temporary, "${endpoint.port}\n${endpoint.token}\n", Charsets.US_ASCII)
            try {
                Files.move(temporary, instanceDirectory.resolve(ENDPOINT_FILE),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, instanceDirectory.resolve(ENDPOINT_FILE), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readEndpoint(path: Path): Endpoint? = runCatching {
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_SIGNAL_BYTES + 1) }
        if (bytes.size > MAX_SIGNAL_BYTES) return null
        val lines = bytes.toString(Charsets.US_ASCII).lines()
        val port = lines.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val token = lines.getOrNull(1)?.takeIf { UUID.fromString(it).toString() == it } ?: return null
        Endpoint(port, token)
    }.getOrNull()

    private fun readLine(socket: Socket, timeoutMillis: Int): String? {
        val input = socket.getInputStream()
        val line = StringBuilder()
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        repeat(MAX_SIGNAL_BYTES) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            socket.soTimeout = (remaining / 1_000_000L).coerceAtLeast(1).toInt()
            val character = input.read()
            if (character < 0) return null
            if (character == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(character.toChar())
        }
        return null
    }

    private data class Endpoint(val port: Int, val token: String)

    companion object {
        private const val LOCK_FILE = "owner.lock"
        private const val ENDPOINT_FILE = "owner.endpoint"
        private const val FOCUS_SIGNAL = "FOCUS"
        private const val ACKNOWLEDGEMENT = "CAMWORK-TRANSLATE"
        private const val PROTOCOL_SIGNAL = "PROTOCOL"
        private const val PROTOCOL_ACKNOWLEDGEMENT = "CAMWORK-TRANSLATE/2"
        private const val MAX_SIGNAL_BYTES = 128
        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        private val owners = ConcurrentHashMap<Path, SingleInstanceGuard>()

        private fun defaultInstanceDirectory(): Path {
            val localData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            return if (System.getProperty("os.name").orEmpty().startsWith("Windows") && localData != null) {
                Path.of(localData, AppConstants.DATA_DIRECTORY_NAME, "instance")
            } else {
                val userHome = requireNotNull(System.getProperty("user.home")?.takeIf { it.isNotBlank() }) {
                    "Не определён пользовательский каталог для блокировки экземпляра"
                }
                Path.of(userHome, ".camwork-translate", "instance")
            }
        }
    }
}
