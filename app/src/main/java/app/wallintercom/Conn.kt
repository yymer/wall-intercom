package app.wallintercom

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import kotlin.concurrent.thread

/**
 * One TCP connection to another tablet. Messages are single-line JSON objects.
 * [onMessage] and [onClosed] are called from the reader thread. Once [session] is set, outgoing
 * messages are signed; incoming ones are passed on as received (the owner unwraps them, so that
 * switching to the session happens in order with the messages).
 */
class Conn(
    private val socket: Socket,
    private val onMessage: (Conn, JSONObject) -> Unit,
    private val onClosed: (Conn) -> Unit,
    /** An existing reader, if the handshake already read from this socket. */
    private val reader: BufferedReader? = null,
) {
    /** Set once both sides proved they share the pair key. */
    @Volatile
    var session: Session? = null
    /** The paired tablet at the other end, once authenticated. */
    @Volatile
    var peerId: String? = null
    /** Server side, between auth and proof: (peer id, client nonce, server nonce). */
    var pendingAuth: Triple<String, String, String>? = null

    private val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    /** Address of the other end of this connection. */
    val remoteAddress: java.net.InetAddress? get() = socket.inetAddress

    @Volatile
    var closed = false
        private set

    fun start(): Conn {
        thread(name = "intercom-conn", isDaemon = true) {
            try {
                val r = reader ?: BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
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
                out.write((session?.wrap(msg) ?: msg).toString())
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
