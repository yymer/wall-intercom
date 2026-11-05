package se.graw.wallintercom

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads the one-line JSON messages from a socket. Anything on the network can connect, so a line
 * has a size limit and a malformed one fails with an exception instead of taking down the app.
 */
class LineReader(private val input: InputStream) {
    private val buf = ByteArray(8192)
    private var pos = 0
    private var end = 0

    /** The next line, or null at the end of the stream. */
    fun readLine(): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            if (pos == end) {
                end = input.read(buf)
                pos = 0
                if (end <= 0) {
                    end = 0
                    return if (line.size() > 0) line.toString("UTF-8") else null
                }
            }
            val start = pos
            while (pos < end && buf[pos] != NEWLINE) pos++
            if (line.size() + (pos - start) > MAX_LINE_BYTES) throw IOException("line too long")
            line.write(buf, start, pos - start)
            if (pos < end) {
                pos++ // the newline
                return line.toString("UTF-8")
            }
        }
    }

    /** The next message, or null at the end of the stream. */
    fun readJson(): JSONObject? {
        val line = readLine() ?: return null
        return parseJson(line)
    }

    companion object {
        /** Much more than the largest real message (a WebRTC offer or answer). */
        const val MAX_LINE_BYTES = 256 * 1024
        private const val NEWLINE = '\n'.code.toByte()

        /** Deeply nested JSON overflows the parser's stack, which isn't an Exception. */
        fun parseJson(text: String): JSONObject = try {
            JSONObject(text)
        } catch (_: StackOverflowError) {
            throw IOException("JSON nested too deeply")
        }
    }
}
