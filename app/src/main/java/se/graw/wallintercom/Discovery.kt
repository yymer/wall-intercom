package se.graw.wallintercom

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Finds the other Wall Intercom tablets on the Wi-Fi with small UDP broadcasts on the same port
 * number as the calls:
 *   who{id,name,port}  "who's there?" — everyone answers with a unicast here{…}
 *   here{id,name,port} "I'm here" — also broadcast every minute
 *
 * This only says *where* a tablet is. Who it is gets proven when connecting (see [Link]), so a
 * fake announcement can at worst make a call fail, never let someone else in.
 */
object Discovery {
    private const val TAG = "Discovery"
    private const val ANNOUNCE_EVERY_S = 60L
    /** Tablets not heard from for this long are dropped from the "new devices" list. */
    private const val FORGET_AFTER_MS = 3 * 60_000L
    private const val MAX_FOUND = 64

    data class Found(val id: String, val name: String, val ip: String, val port: Int, val seen: Long)

    private lateinit var prefs: Prefs
    private val found = HashMap<String, Found>()
    private val timers = Executors.newSingleThreadScheduledExecutor()
    @Volatile
    private var socket: DatagramSocket? = null
    private var started = false

    /** Called (on a background thread) when the set of found tablets or their addresses changes. */
    var onChange: (() -> Unit)? = null

    @Synchronized
    fun start(p: Prefs) {
        if (started) return
        started = true
        prefs = p
        thread(name = "intercom-discovery", isDaemon = true) { listenLoop() }
        timers.scheduleWithFixedDelay({ send("here", broadcast = true); prune() }, 2, ANNOUNCE_EVERY_S, TimeUnit.SECONDS)
    }

    /** Re-bind (after the port changed in settings). */
    fun restart() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    /** Ask everyone on the network to say where they are. */
    fun search() = timers.execute { send("who", broadcast = true) }

    /** Recently seen tablets, including paired ones. */
    fun found(): List<Found> = synchronized(found) {
        val now = System.currentTimeMillis()
        found.values.filter { now - it.seen < FORGET_AFTER_MS }
    }

    /**
     * Asks where tablet [id] is and waits up to [timeoutMs] for it to answer.
     * Blocking: don't call on the main thread.
     */
    fun locate(id: String, timeoutMs: Long): Found? {
        val start = System.currentTimeMillis()
        send("who", broadcast = true)
        synchronized(found) {
            while (true) {
                found[id]?.takeIf { it.seen >= start }?.let { return it }
                val left = timeoutMs - (System.currentTimeMillis() - start)
                if (left <= 0) return null
                (found as Object).wait(left)
            }
        }
    }

    private fun listenLoop() {
        while (true) {
            try {
                DatagramSocket(null).use { s ->
                    s.reuseAddress = true
                    s.broadcast = true
                    s.bind(InetSocketAddress(prefs.port))
                    socket = s
                    Log.i(TAG, "listening on udp ${prefs.port}")
                    send("who", broadcast = true) // find everyone right away
                    val buf = ByteArray(2048)
                    while (!s.isClosed) {
                        val pkt = DatagramPacket(buf, buf.size)
                        s.receive(pkt)
                        onPacket(pkt)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "listener: ${e.message}")
            }
            socket = null
            Thread.sleep(1000)
        }
    }

    private fun onPacket(pkt: DatagramPacket) {
        val m = try {
            JSONObject(String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val id = m.optString("id")
        if (id.isBlank() || id == prefs.deviceId) return // our own broadcast
        val ip = pkt.address.hostAddress ?: return
        val name = m.optString("name").take(60)
        val port = m.optInt("port", prefs.port)
        when (m.optString("t")) {
            "who" -> {
                record(id, name, ip, port)
                send("here", to = InetSocketAddress(pkt.address, pkt.port))
            }
            "here" -> record(id, name, ip, port)
        }
    }

    private fun record(id: String, name: String, ip: String, port: Int) {
        val f = Found(id, name, ip, port, System.currentTimeMillis())
        val changed = synchronized(found) {
            // Announcements are free to fake: don't let them fill up the memory and the screen.
            if (found.size >= MAX_FOUND && id !in found && Peers.get(id) == null) return
            val old = found.put(id, f)
            (found as Object).notifyAll()
            old == null || old.ip != ip || old.name != name || old.port != port
        }
        // Keep paired tablets' addresses up to date. Their names come from the signed ping replies,
        // since anyone can send an announcement.
        if (Peers.get(id) != null) Peers.updateAddress(id, ip, port)
        if (changed) onChange?.invoke()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val removed = synchronized(found) { found.values.removeAll { now - it.seen > FORGET_AFTER_MS } }
        if (removed) onChange?.invoke()
    }

    private fun send(type: String, broadcast: Boolean = false, to: InetSocketAddress? = null) {
        val s = socket ?: return
        val data = JSONObject().put("t", type).put("id", prefs.deviceId).put("name", prefs.myName)
            .put("port", prefs.port).put("v", 2).toString().toByteArray(Charsets.UTF_8)
        val targets = if (broadcast) broadcastAddresses().map { InetSocketAddress(it, prefs.port) } else listOfNotNull(to)
        for (t in targets) {
            try {
                s.send(DatagramPacket(data, data.size, t))
            } catch (e: Exception) {
                Log.d(TAG, "send to $t failed: ${e.message}")
            }
        }
    }

    /** 255.255.255.255 plus each network's own broadcast address (some routers only pass those). */
    private fun broadcastAddresses(): Set<InetAddress> {
        val out = linkedSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                ni.interfaceAddresses.forEach { a ->
                    if (a.address is Inet4Address) a.broadcast?.let { out += it }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}
