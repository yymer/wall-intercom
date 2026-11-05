package app.wallintercom

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import kotlin.concurrent.thread

/**
 * One TCP connection to the other tablet. Messages are single-line JSON objects.
 * [onMessage] and [onClosed] are called from the reader thread.
 */
class Conn(
    private val socket: Socket,
    private val onMessage: (Conn, JSONObject) -> Unit,
    private val onClosed: (Conn) -> Unit,
) {
    private val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    /** Address of the other end of this connection. */
    val remoteAddress: java.net.InetAddress? get() = socket.inetAddress

    @Volatile
    var closed = false
        private set

    fun start(): Conn {
        thread(name = "intercom-conn", isDaemon = true) {
            try {
                val r = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isNotBlank()) onMessage(this, JSONObject(line))
                }
            } catch (_: Exception) {
            }
            close()
            onClosed(this)
        }
        return this
    }

    fun send(msg: JSONObject) {
        if (closed) return
        try {
            synchronized(out) {
                out.write(msg.toString())
                out.write("\n")
                out.flush()
            }
        } catch (_: Exception) {
            close()
        }
    }

    fun close() {
        closed = true
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
