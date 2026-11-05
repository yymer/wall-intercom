package app.wallintercom

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Outgoing connections to other tablets. Blocking: call from a background thread.
 *
 * Connecting to a paired tablet (mutual proof that both know the pair key, then every message is
 * signed — see [Session]):
 *   client -> auth{id,n}                 server -> chal{id,n,h}   (or "unknown": it forgot us)
 *   client -> proof{h}                   ...then signed messages both ways
 *
 * Pairing with a new tablet (no key yet):
 *   client -> pair{id,name,port,pub}     server -> pairwait{id,name,pub}  (both show the code)
 *                                        server -> pairok | pairno{why}   (after Accept/Decline)
 */
object Link {
    private const val TAG = "Link"
    const val PROTOCOL = 2

    class Opened(val socket: Socket, val reader: BufferedReader, val session: Session, val peer: Peer) {
        fun send(m: JSONObject) {
            val out = socket.getOutputStream()
            out.write((session.wrap(m).toString() + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }

        /** Next message, or null if the connection ended or a message was forged. */
        fun read(): JSONObject? = reader.readLine()?.let { session.unwrap(JSONObject(it)) }

        fun close() = try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    sealed class Result {
        class Ok(val link: Opened) : Result()
        /** Not reachable at its last address, nor found on the network. */
        object Unreachable : Result()
        /** It answered but doesn't know us anymore (reset, reinstalled or removed us). */
        object Forgotten : Result()
    }

    /**
     * Connects to [peer] and proves who both sides are. If its last known address doesn't work,
     * asks the network where it is now and tries once more.
     */
    fun open(prefs: Prefs, peer: Peer, connectTimeoutMs: Int = 3000): Result {
        val first = peer.ip?.let { tryOpen(prefs, peer, it, peer.port, connectTimeoutMs) }
        if (first != null && first !is Result.Unreachable) return first
        val f = Discovery.locate(peer.id, 2500) ?: return Result.Unreachable
        if (f.ip == peer.ip && f.port == peer.port && first != null) return Result.Unreachable
        Log.i(TAG, "${peer.name} is now at ${f.ip}")
        Peers.updateAddress(peer.id, f.ip, f.port)
        return tryOpen(prefs, peer.copy(ip = f.ip, port = f.port), f.ip, f.port, connectTimeoutMs)
    }

    private fun tryOpen(prefs: Prefs, peer: Peer, ip: String, port: Int, connectTimeoutMs: Int): Result {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(ip, port), connectTimeoutMs)
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 5000
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val out = s.getOutputStream()
            fun raw(m: JSONObject) {
                out.write((m.toString() + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val nc = Crypto.nonce()
            raw(JSONObject().put("t", "auth").put("id", prefs.deviceId).put("n", nc).put("v", PROTOCOL))
            val chal = JSONObject(r.readLine() ?: throw Exception("closed"))
            when (chal.optString("t")) {
                "unknown" -> {
                    s.close()
                    return Result.Forgotten
                }
                "chal" -> {}
                else -> throw Exception("unexpected ${chal.optString("t")}")
            }
            val ns = chal.getString("n")
            // A different tablet at this address, or one that can't prove it has the key.
            if (chal.optString("id") != peer.id ||
                !Crypto.same(chal.optString("h"), Crypto.hmacB64(peer.key, "server", nc, ns))
            ) {
                Log.w(TAG, "$ip is not ${peer.name}")
                s.close()
                return Result.Unreachable
            }
            raw(JSONObject().put("t", "proof").put("h", Crypto.hmacB64(peer.key, "client", nc, ns)))
            s.soTimeout = 0
            if (ip != peer.ip) Peers.updateAddress(peer.id, ip, port)
            return Result.Ok(Opened(s, r, Session(peer.key, nc, ns, isClient = true), peer))
        } catch (e: Exception) {
            Log.d(TAG, "can't open $ip:$port (${peer.name}): ${e.message}")
            try {
                s.close()
            } catch (_: Exception) {
            }
            return Result.Unreachable
        }
    }

    /** Sends one message and returns the reply (ping, bell, unpair). */
    fun request(prefs: Prefs, peer: Peer, msg: JSONObject, timeoutMs: Int = 3000): Pair<Result, JSONObject?> {
        val r = open(prefs, peer, 2000)
        if (r !is Result.Ok) return r to null
        val l = r.link
        return try {
            l.socket.soTimeout = timeoutMs
            l.send(msg)
            r to l.read()
        } catch (e: Exception) {
            Result.Unreachable to null
        } finally {
            l.close()
        }
    }

    // ---------------------------------------------------------------- pairing

    interface PairListener {
        /** Both tablets now show [code]; waiting for someone there to press Accept. */
        fun onCode(name: String, code: String)
        fun onPaired(peer: Peer)
        fun onFailed(why: PairFailure, name: String?)
    }

    enum class PairFailure { UNREACHABLE, DECLINED, BUSY, TIMEOUT, ALREADY_PAIRING }

    class Pairing(private val socket: Socket) {
        @Volatile
        var cancelled = false
            private set

        fun cancel() {
            cancelled = true
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Asks the tablet at [ip]:[port] to pair. Runs on its own thread; [l] is called from it. */
    fun pair(prefs: Prefs, ip: String, port: Int, l: PairListener): Pairing {
        val s = Socket()
        val p = Pairing(s)
        Thread({
            var name: String? = null
            try {
                s.connect(InetSocketAddress(ip, port), 4000)
                s.soTimeout = 8000
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val out = s.getOutputStream()
                val me = Crypto.Pairing()
                val req = JSONObject().put("t", "pair").put("id", prefs.deviceId).put("name", prefs.myName)
                    .put("port", prefs.port).put("pub", me.publicKey).put("v", PROTOCOL)
                out.write((req.toString() + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                val wait = JSONObject(r.readLine() ?: throw Exception("closed"))
                if (wait.optString("t") == "pairno") {
                    l.onFailed(failure(wait), null)
                    return@Thread
                }
                if (wait.optString("t") != "pairwait") throw Exception("unexpected ${wait.optString("t")}")
                val id = wait.getString("id")
                name = wait.optString("name").ifBlank { ip }
                val res = me.finish(prefs.deviceId, id, wait.getString("pub"))
                l.onCode(name, res.code)
                s.soTimeout = 75_000 // the other tablet gives up after 60 s
                val answer = JSONObject(r.readLine() ?: throw Exception("closed"))
                if (answer.optString("t") == "pairok") {
                    val peer = Peer(id, name, res.key, ip, port)
                    Peers.put(peer)
                    Log.i(TAG, "paired with $name")
                    l.onPaired(peer)
                } else {
                    l.onFailed(failure(answer), name)
                }
            } catch (e: Exception) {
                if (!p.cancelled) {
                    Log.w(TAG, "pairing with $ip failed: ${e.message}")
                    l.onFailed(if (name == null) PairFailure.UNREACHABLE else PairFailure.TIMEOUT, name)
                }
            } finally {
                try {
                    s.close()
                } catch (_: Exception) {
                }
            }
        }, "intercom-pair").start()
        return p
    }

    private fun failure(m: JSONObject) = when (m.optString("why")) {
        "busy" -> PairFailure.BUSY
        "pairing" -> PairFailure.ALREADY_PAIRING
        "timeout" -> PairFailure.TIMEOUT
        else -> PairFailure.DECLINED
    }
}
