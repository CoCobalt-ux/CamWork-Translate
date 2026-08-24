package com.github.ahatem.qtranslate.app

import java.io.PrintWriter
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Удерживает порт на localhost, пока приложение работает. Занятый порт означает, что копия уже
 * запущена: новый процесс просит её показать окно и завершается, вместо того чтобы открывать
 * второе окно поверх первого.
 */
object SingleInstanceGuard {

    /**
     * Выбран вне диапазона временных портов (49152–65535), из которого система раздаёт порты
     * исходящим соединениям.
     *
     * Прежний 49231 лежал внутри этого диапазона, поэтому его мог занять любой посторонний
     * процесс — и тогда приложение принимало чужой сокет за собственную копию и молча
     * отказывалось запускаться, без окна и без записи в журнале.
     */
    private const val PORT = 28731

    private const val FOCUS_SIGNAL = "FOCUS"

    /**
     * Ответ, по которому новый процесс отличает свою копию от случайного соседа по порту.
     * Без него достаточно было любого слушателя на этом порту, чтобы запуск стал невозможен.
     */
    private const val ACKNOWLEDGEMENT = "CAMWORK-TRANSLATE"

    private const val HANDSHAKE_TIMEOUT_MS = 1500

    private var serverSocket: ServerSocket? = null

    /**
     * @return `true`, если запуск нужно продолжить: либо порт свободен, либо его держит
     *   постороннее приложение, из-за которого отказываться от запуска незачем.
     */
    fun tryLock(onFocusRequested: () -> Unit): Boolean {
        return try {
            val socket = ServerSocket(PORT, 1, InetAddress.getByName("localhost"))
            serverSocket = socket
            Thread {
                // Закрытый сокет иначе даёт исключение на каждой итерации, и поток при выходе
                // из приложения крутится вхолостую.
                while (!socket.isClosed) {
                    runCatching {
                        socket.accept().use { client ->
                            val signal = client.getInputStream().bufferedReader().readLine()
                            if (signal == FOCUS_SIGNAL) {
                                PrintWriter(client.getOutputStream(), true).println(ACKNOWLEDGEMENT)
                                onFocusRequested()
                            }
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
            true
        } catch (_: BindException) {
            !isPortHeldByOwnInstance()
        }
    }

    fun release() {
        serverSocket?.close()
    }

    private fun isPortHeldByOwnInstance(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(InetAddress.getByName("localhost"), PORT),
                HANDSHAKE_TIMEOUT_MS
            )
            // Иначе молчаливый собеседник задержал бы запуск на неопределённое время.
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            PrintWriter(socket.getOutputStream(), true).println(FOCUS_SIGNAL)
            socket.getInputStream().bufferedReader().readLine() == ACKNOWLEDGEMENT
        }
    }.getOrDefault(false)
}
