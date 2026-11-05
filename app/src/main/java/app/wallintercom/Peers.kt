package app.wallintercom

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A tablet this one is paired with. [key] is the secret only these two tablets know; it proves
 * who is calling, whatever IP address either tablet has. [ip]/[port] are where it was last seen.
 */
data class Peer(val id: String, val name: String, val key: ByteArray, val ip: String?, val port: Int)

/** The paired tablets, stored in the settings. Thread-safe. */
object Peers {
    private lateinit var prefs: Prefs
    private val lock = Any()
    private var list: List<Peer> = emptyList()

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = Prefs(ctx)
        list = load()
    }

    fun all(): List<Peer> = synchronized(lock) { list }
    fun get(id: String?): Peer? = synchronized(lock) { list.firstOrNull { it.id == id } }

    /** Adds or replaces (re-pairing) a peer. */
    fun put(p: Peer) = change { l -> l.filter { it.id != p.id } + p }
    fun remove(id: String) = change { l -> l.filter { it.id != id } }

    fun updateAddress(id: String, ip: String, port: Int? = null) = change { l ->
        l.map { if (it.id == id && (it.ip != ip || (port != null && it.port != port))) it.copy(ip = ip, port = port ?: it.port) else it }
    }

    fun updateName(id: String, name: String) {
        if (name.isBlank()) return
        change { l -> l.map { if (it.id == id && it.name != name) it.copy(name = name) else it } }
    }

    private fun change(f: (List<Peer>) -> List<Peer>) {
        synchronized(lock) {
            val new = f(list)
            if (new == list) return
            list = new
            save(new)
        }
    }

    private fun load(): List<Peer> = try {
        val a = JSONArray(prefs.peersJson ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Peer(
                o.getString("id"), o.getString("name"), Crypto.unb64(o.getString("key")),
                o.optString("ip").ifBlank { null }, o.optInt("port", prefs.port),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(l: List<Peer>) {
        val a = JSONArray()
        l.forEach {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name).put("key", Crypto.b64(it.key))
                    .put("ip", it.ip ?: "").put("port", it.port)
            )
        }
        prefs.peersJson = a.toString()
    }
}

object Crypto {
    private val rnd = SecureRandom()

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    fun nonce(): String = ByteArray(16).also { rnd.nextBytes(it) }.let(::b64)

    fun hmac(key: ByteArray, vararg parts: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEachIndexed { i, p ->
            if (i > 0) mac.update(0)
            mac.update(p.toByteArray(Charsets.UTF_8))
        }
        return mac.doFinal()
    }

    fun hmacB64(key: ByteArray, vararg parts: String) = b64(hmac(key, *parts))

    /** Constant-time comparison of two base64 MACs. */
    fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /**
     * One side of a pairing: an ephemeral elliptic-curve key pair. Both tablets combine their own
     * private key with the other's public key into the same secret (ECDH). Someone in the middle
     * would end up with a different secret on each side, and so a different code on each screen.
     */
    class Pairing {
        private val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val publicKey: String = b64(kp.public.encoded)

        class Result(val key: ByteArray, val code: String)

        fun finish(myId: String, otherId: String, otherPublicKey: String): Result {
            val otherPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(otherPublicKey)))
            val shared = KeyAgreement.getInstance("ECDH").run {
                init(kp.private)
                doPhase(otherPub, true)
                generateSecret()
            }
            // Same order on both tablets.
            val (idA, pubA, idB, pubB) = if (myId < otherId) listOf(myId, publicKey, otherId, otherPublicKey)
            else listOf(otherId, otherPublicKey, myId, publicKey)
            val key = hmac(shared, "wall-intercom pair key", idA, idB)
            val h = hmac(shared, "wall-intercom pair code", idA, pubA, idB, pubB)
            val n = java.nio.ByteBuffer.wrap(h).int.toLong() and 0xffffffffL
            return Result(key, "%04d".format(n % 10000))
        }
    }
}

/**
 * An authenticated connection: every message carries a sequence number and a MAC made with a key
 * derived from the pair key and both sides' nonces, so messages can't be forged, replayed or
 * reordered by someone else on the network. (Not encrypted: the content isn't secret, and the
 * audio/video itself is encrypted by WebRTC.)
 */
class Session(pairKey: ByteArray, nonceClient: String, nonceServer: String, private val isClient: Boolean) {
    private val key = Crypto.hmac(pairKey, "wall-intercom session", nonceClient, nonceServer)
    private var sendSeq = 0L
    private var recvSeq = 0L
    private val myDir = if (isClient) "c" else "s"
    private val theirDir = if (isClient) "s" else "c"

    @Synchronized
    fun wrap(msg: JSONObject): JSONObject {
        val m = msg.toString()
        val seq = sendSeq++
        return JSONObject().put("s", seq).put("m", m).put("h", Crypto.hmacB64(key, myDir, seq.toString(), m))
    }

    /** The inner message, or null if it was tampered with or out of order. */
    @Synchronized
    fun unwrap(w: JSONObject): JSONObject? {
        val seq = w.optLong("s", -1)
        val m = w.optString("m")
        val h = w.optString("h")
        if (seq != recvSeq || !Crypto.same(h, Crypto.hmacB64(key, theirDir, seq.toString(), m))) return null
        recvSeq++
        return try {
            JSONObject(m)
        } catch (_: Exception) {
            null
        }
    }
}
