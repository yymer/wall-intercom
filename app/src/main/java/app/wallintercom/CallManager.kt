package app.wallintercom

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * DIALING: we called, waiting for the other tablet to respond.
 * RINGING: we called, the other tablet is ringing (waiting for someone there to answer).
 * INCOMING: the other tablet is calling us; camera/mic stay off until someone presses Answer.
 * AUTO_ANSWERING: auto-answer is on; the bell is playing and the call starts when it ends.
 */
enum class CallState { IDLE, DIALING, RINGING, INCOMING, AUTO_ANSWERING, CONNECTING, IN_CALL, RECONNECTING }

/** Forwards video frames to whatever renderer is currently attached. */
class ProxyVideoSink : VideoSink {
    @Volatile
    var target: VideoSink? = null
    override fun onFrame(frame: VideoFrame) {
        target?.onFrame(frame)
    }
}

/**
 * Peer-to-peer intercom between paired tablets.
 *
 * Each tablet listens on a TCP port (calls) and the same UDP port ([Discovery]). Every TCP
 * connection from a paired tablet starts with a mutual proof of the pair key and then carries
 * signed one-line JSON messages (see [Link] and [Session]):
 *   caller -> hello{sid}          callee -> ok (answered) | busy
 *                                 callee -> ringing, then later ok | decline | noanswer
 *   caller -> offer{sdp}          callee -> answer{sdp}
 *   both   -> ice{mid,idx,sdp}    either -> bye
 *   caller -> resume{sid}         callee -> resumed | gone   (new socket after the old one died)
 *   ping -> pong{name}            (short connection, for the "online" dots)
 *   bell -> bellok                (short connection, rings the bell on the other tablet)
 *   unpair -> (closed)            (the other tablet removed us)
 * An unpaired tablet may only send pair{…} (see [Link.pair]); someone here must press Accept.
 *
 * If the network hiccups during a call (ICE disconnected/failed or the signalling socket dies),
 * the call goes to RECONNECTING for up to [RECONNECT_GRACE_MS]. The caller re-opens the
 * signalling socket if needed ("resume") and does an ICE restart; the callee just waits.
 *
 * All call state is only touched on the single [exec] thread.
 */
object CallManager {
    private const val TAG = "CallManager"
    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val RECONNECT_GRACE_MS = 30_000L
    private const val RECONNECT_TICK_MS = 3_000L
    private const val ICE_RESTART_INTERVAL_MS = 6_000L
    /** How long an incoming call rings before it counts as missed. */
    private const val RING_TIMEOUT_MS = 45_000L
    private const val RING_REPEAT_MS = 4_000L
    /** A bit longer than the bell sound (2.6 s). */
    private const val BELL_FALLBACK_MS = 4_000L
    /** How long a pairing request waits for Accept here. */
    private const val PAIR_TIMEOUT_MS = 60_000L
    /**
     * A paired tablet not heard from for this long is really offline (turned off, out of range)
     * and is hidden from the main screen until it's back. Shorter outages just show a grey dot.
     * Pings run every 20 s, so this is about three missed pings.
     */
    private const val HIDE_OFFLINE_AFTER_MS = 60_000L

    interface Listener {
        fun onCallState(state: CallState, peer: String, info: String?)
        /** Paired/found tablets or their online status changed: see [rooms]. */
        fun onRoomsChanged()
        fun onBell(from: String)
        /** Our outgoing call was declined or not answered; shown next to the Call button. */
        fun onCallNotAnswered(info: String)
        /** Another tablet asks to pair: show [code], then call [answerPairing]. */
        fun onPairRequest(name: String, code: String)
        /** The pairing request was answered, cancelled or timed out: hide it. */
        fun onPairRequestEnded()
    }

    /** One row on the main screen: a paired tablet, or a new one found on the network. */
    data class Room(val id: String, val name: String, val paired: Boolean, val online: Boolean, val ip: String, val port: Int)

    private class PendingPair(val c: Conn, val id: String, val name: String, val port: Int, val key: ByteArray, val code: String)

    private lateinit var ctx: Context
    private lateinit var prefs: Prefs
    lateinit var eglBase: EglBase
        private set

    private val exec = Executors.newSingleThreadExecutor()
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false

    /** Set/cleared by the visible activity, main thread only. */
    var listener: Listener? = null
    /** Called on the main thread on an incoming call, bell or pairing request (wake + show). */
    var onWakeUp: (() -> Unit)? = null

    /** Last bell received: (from, time). The activity shows it when it comes to the front. */
    @Volatile
    var lastBell: Pair<String, Long>? = null
        private set

    @Volatile
    var state = CallState.IDLE
        private set
    /** Name of the tablet we're in a call with (or calling / being called by). */
    @Volatile
    var callPeerName = ""
        private set
    private var callPeer: Peer? = null

    /** Paired tablets that answered the last ping. */
    private val online = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** When each paired tablet last answered or connected to us. */
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** A pairing request waiting for Accept here: (name, code), for the activity on resume. */
    @Volatile
    var pairRequest: Pair<String, String>? = null
        private set
    private var pendingPair: PendingPair? = null

    val localSink = ProxyVideoSink()
    val remoteSink = ProxyVideoSink()

    private var conn: Conn? = null
    private var callId = 0
    private var factory: PeerConnectionFactory? = null
    private var adm: AudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideo: VideoTrack? = null
    private var remoteDescSet = false
    private val pendingIce = mutableListOf<IceCandidate>()
    private var iceState: PeerConnection.IceConnectionState? = null
    private var audioActive = false
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var sessionId = ""
    private var isCaller = false
    private var reconnectDeadline = 0L
    private var lastIceRestart = 0L
    private var resumeInFlight = false
    /** Bumped whenever a connect/ring timeout is armed, so only the latest one can fire. */
    private var timeoutGen = 0

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        ctx = context.applicationContext
        prefs = Prefs(ctx)
        Peers.init(ctx)
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions()
        )
        thread(name = "intercom-listener", isDaemon = true) { listenLoop() }
        Discovery.onChange = { roomsChanged() }
        Discovery.start(prefs)
        timers.scheduleWithFixedDelay({ pingAll() }, 3, 20, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- public API

    /**
     * Paired tablets first (in pairing order), then new ones found on the network. Paired tablets
     * that have been unreachable for a while are left out until they're back.
     */
    fun rooms(): List<Room> {
        val peers = Peers.all()
        val found = Discovery.found()
        val now = System.currentTimeMillis()
        val paired = peers.filter { p ->
            val seen = maxOf(lastSeen[p.id] ?: 0L, found.firstOrNull { it.id == p.id }?.seen ?: 0L)
            online[p.id] == true || now - seen < HIDE_OFFLINE_AFTER_MS
        }.map { p ->
            Room(p.id, p.name, true, online[p.id] == true, p.ip ?: "", p.port)
        }
        val new = found.filter { f -> peers.none { it.id == f.id } }
            .sortedBy { it.name }
            .map { Room(it.id, it.name.ifBlank { it.ip }, false, true, it.ip, it.port) }
        return paired + new
    }

    fun dial(peerId: String) = exec.execute {
        if (state != CallState.IDLE) return@execute
        val peer = Peers.get(peerId) ?: return@execute
        val id = ++callId
        callPeer = peer
        callPeerName = peer.name
        setState(CallState.DIALING)
        val r = Link.open(prefs, peer, 4000) // blocks this thread for a moment, like before
        if (callId != id || state != CallState.DIALING) {
            (r as? Link.Result.Ok)?.link?.close()
            return@execute
        }
        val link = when (r) {
            is Link.Result.Ok -> r.link
            Link.Result.Forgotten -> {
                forgotten(peer)
                setState(CallState.IDLE, ctx.getString(R.string.err_forgotten, peer.name))
                return@execute
            }
            Link.Result.Unreachable -> {
                setOnline(peer.id, false)
                setState(CallState.IDLE, ctx.getString(R.string.err_unreachable, peer.name))
                return@execute
            }
        }
        setOnline(peer.id, true)
        val c = newConn(link.socket, link.reader)
        c.session = link.session
        c.peerId = peer.id
        conn = c
        isCaller = true
        sessionId = UUID.randomUUID().toString()
        c.start()
        c.send(msg("hello").put("sid", sessionId))
        armTimeout(id, CONNECT_TIMEOUT_MS)
    }

    fun hangup() = exec.execute {
        Log.i(TAG, "End call pressed")
        when (state) {
            CallState.IDLE -> {}
            CallState.INCOMING -> {
                conn?.send(msg("decline"))
                endCall(null, notifyPeer = false)
            }
            else -> endCall(null)
        }
    }

    /** Answer a ringing incoming call: only now are the camera and microphone turned on. */
    fun answer() = exec.execute {
        if (state != CallState.INCOMING) return@execute
        val c = conn ?: return@execute
        Log.i(TAG, "Answer pressed")
        startAnswered(c)
    }

    /** Check now which rooms are online, and look for new tablets. */
    fun pingNow() {
        Discovery.search()
        timers.execute { pingAll() }
    }

    /**
     * Rings the bell on [peerId]. [onDone] is called on the main thread with whether the other
     * tablet received it; failures are also shown as an info message.
     */
    fun sendBell(peerId: String, onDone: (Boolean) -> Unit) = timers.execute {
        val peer = Peers.get(peerId)
        if (peer == null) {
            main.post { onDone(false) }
            return@execute
        }
        val (r, reply) = Link.request(prefs, peer, msg("bell"))
        val ok = reply?.optString("t") == "bellok"
        when {
            r is Link.Result.Forgotten -> {
                forgotten(peer)
                notifyInfo(ctx.getString(R.string.err_forgotten, peer.name))
            }
            !ok -> notifyInfo(ctx.getString(R.string.err_unreachable, peer.name))
        }
        setOnline(peer.id, ok)
        main.post { onDone(ok) }
    }

    /** Removes a paired tablet here, and tells it to forget us too (if it's reachable). */
    fun unpair(peerId: String) {
        val peer = Peers.get(peerId) ?: return
        Log.i(TAG, "removing ${peer.name}")
        Peers.remove(peerId)
        online.remove(peerId)
        roomsChanged()
        // Uses the copy: it still has the key needed to prove it's us.
        timers.execute { Link.request(prefs, peer, msg("unpair"), 2000) }
    }

    /** Accept or decline the pairing request shown by [Listener.onPairRequest]. */
    fun answerPairing(accept: Boolean) = exec.execute {
        val p = pendingPair ?: return@execute
        pendingPair = null
        pairRequest = null
        if (accept) {
            val ip = p.c.remoteAddress?.hostAddress
            Peers.put(Peer(p.id, p.name, p.key, ip, p.port))
            Log.i(TAG, "paired with ${p.name}")
            p.c.send(msg("pairok"))
            setOnline(p.id, true)
        } else {
            p.c.send(msg("pairno").put("why", "declined"))
        }
        p.c.close()
        roomsChanged()
        main.post { listener?.onPairRequestEnded() }
    }

    /** Re-bind the listening sockets (e.g. after the port changed). */
    fun restartListener() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        Discovery.restart()
    }

    // ---------------------------------------------------------------- networking

    private fun listenLoop() {
        while (true) {
            try {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(prefs.port))
                    serverSocket = ss
                    Log.i(TAG, "listening on ${prefs.port}")
                    while (!ss.isClosed) {
                        val s = ss.accept()
                        s.tcpNoDelay = true
                        s.keepAlive = true
                        newConn(s).start()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "listener: ${e.message}")
            }
            serverSocket = null
            Thread.sleep(1000)
        }
    }

    private fun newConn(s: Socket, reader: java.io.BufferedReader? = null) = Conn(
        s,
        onMessage = { c, m -> exec.execute { onMessage(c, m) } },
        onClosed = { c -> exec.execute { onClosed(c) } },
        reader = reader,
    )

    private fun pingAll() {
        for (peer in Peers.all()) {
            if (state != CallState.IDLE && callPeer?.id == peer.id) {
                setOnline(peer.id, true)
                continue
            }
            val (r, reply) = Link.request(prefs, peer, msg("ping"))
            if (r is Link.Result.Forgotten) {
                forgotten(peer)
                continue
            }
            val ok = reply?.optString("t") == "pong"
            if (ok) reply!!.optString("name").let { Peers.updateName(peer.id, it) }
            setOnline(peer.id, ok)
        }
        // Rooms may have crossed the "really offline" line without their dot changing.
        roomsChanged()
    }

    /** The other tablet doesn't know us anymore (reset or removed us): drop it here too. */
    private fun forgotten(peer: Peer) {
        Log.i(TAG, "${peer.name} doesn't know this tablet anymore, removing it")
        Peers.remove(peer.id)
        online.remove(peer.id)
        roomsChanged()
    }

    private fun onMessage(c: Conn, raw: JSONObject) {
        val session = c.session
        val m = if (session != null) {
            session.unwrap(raw) ?: run {
                Log.w(TAG, "dropping connection: bad signature from ${c.remoteAddress?.hostAddress}")
                c.close()
                return
            }
        } else raw
        val peer = Peers.get(c.peerId)
        if (peer == null) {
            onUnauthenticated(c, m)
            return
        }
        when (m.optString("t")) {
            "ping" -> {
                c.send(msg("pong").put("name", prefs.myName))
                c.close()
            }
            "bell" -> {
                c.send(msg("bellok"))
                c.close()
                onBellReceived(peer)
            }
            "unpair" -> {
                Log.i(TAG, "${peer.name} removed this tablet")
                c.close()
                Peers.remove(peer.id)
                online.remove(peer.id)
                roomsChanged()
            }
            "hello" -> onHello(c, peer, m.optString("sid"))
            "resume" -> onResumeRequest(c, peer, m.optString("sid"))
            else -> if (c === conn) onCallMessage(m)
        }
    }

    /** Server side, before the other end proved who it is: only auth/proof and pairing. */
    private fun onUnauthenticated(c: Conn, m: JSONObject) {
        when (m.optString("t")) {
            "auth" -> {
                val p = Peers.get(m.optString("id"))
                if (p == null) {
                    c.send(msg("unknown"))
                    c.close()
                    return
                }
                val nc = m.optString("n")
                val ns = Crypto.nonce()
                c.pendingAuth = Triple(p.id, nc, ns)
                c.send(msg("chal").put("id", prefs.deviceId).put("n", ns).put("h", Crypto.hmacB64(p.key, "server", nc, ns)))
            }
            "proof" -> {
                val (id, nc, ns) = c.pendingAuth ?: return c.close()
                val p = Peers.get(id) ?: return c.close()
                if (!Crypto.same(m.optString("h"), Crypto.hmacB64(p.key, "client", nc, ns))) {
                    Log.w(TAG, "wrong proof from ${c.remoteAddress?.hostAddress} claiming to be ${p.name}")
                    c.close()
                    return
                }
                c.pendingAuth = null
                c.session = Session(p.key, nc, ns, isClient = false)
                c.peerId = p.id
                c.remoteAddress?.hostAddress?.let { Peers.updateAddress(p.id, it) }
                setOnline(p.id, true)
            }
            "pair" -> onPairRequest(c, m)
            else -> {
                Log.w(TAG, "ignoring unauthenticated '${m.optString("t")}' from ${c.remoteAddress?.hostAddress}")
                c.close()
            }
        }
    }

    // ---------------------------------------------------------------- pairing (this side)

    private fun onPairRequest(c: Conn, m: JSONObject) {
        val id = m.optString("id")
        val name = m.optString("name").take(60).ifBlank { c.remoteAddress?.hostAddress ?: "?" }
        val pub = m.optString("pub")
        when {
            id.isBlank() || pub.isBlank() || id == prefs.deviceId -> return c.close()
            state != CallState.IDLE -> {
                c.send(msg("pairno").put("why", "busy")); c.close(); return
            }
            pendingPair != null -> {
                c.send(msg("pairno").put("why", "pairing")); c.close(); return
            }
        }
        val res = try {
            val me = Crypto.Pairing()
            val r = me.finish(prefs.deviceId, id, pub)
            c.send(msg("pairwait").put("id", prefs.deviceId).put("name", prefs.myName).put("pub", me.publicKey))
            r
        } catch (e: Exception) {
            Log.w(TAG, "bad pairing request", e)
            c.close()
            return
        }
        Log.i(TAG, "pairing request from $name, code ${res.code}")
        val p = PendingPair(c, id, name, m.optInt("port", prefs.port), res.key, res.code)
        pendingPair = p
        pairRequest = name to res.code
        playBell()
        main.post {
            onWakeUp?.invoke()
            listener?.onPairRequest(name, res.code)
        }
        timers.schedule({
            exec.execute {
                if (pendingPair === p) {
                    Log.i(TAG, "nobody answered the pairing request")
                    pendingPair = null
                    pairRequest = null
                    p.c.send(msg("pairno").put("why", "timeout"))
                    p.c.close()
                    main.post { listener?.onPairRequestEnded() }
                }
            }
        }, PAIR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    // ---------------------------------------------------------------- calls

    private fun onHello(c: Conn, peer: Peer, sid: String) {
        val same = callPeer?.id == peer.id
        when {
            state == CallState.IDLE -> if (prefs.autoAnswer) autoAnswer(c, peer, sid) else ring(c, peer, sid)
            // The other tablet gave up on a broken call and is calling again: start over.
            state == CallState.RECONNECTING && same -> {
                endCall(null, notifyPeer = false)
                accept(c, peer, sid)
            }
            // Both pressed "call" at the same moment: the tablet whose id sorts first keeps
            // its outgoing call, the other one drops its own and accepts (no need to ring:
            // someone here just pressed Call).
            state == CallState.DIALING && same && prefs.deviceId > peer.id -> {
                val old = conn
                conn = null
                old?.close()
                accept(c, peer, sid)
            }
            else -> {
                c.send(msg("busy"))
                c.close()
            }
        }
    }

    private fun takeIncoming(c: Conn, peer: Peer, sid: String) {
        conn = c
        isCaller = false
        sessionId = sid
        ++callId
        callPeer = peer
        callPeerName = peer.name
    }

    /** Answer right away: both tablets called each other at the same moment. */
    private fun accept(c: Conn, peer: Peer, sid: String) {
        takeIncoming(c, peer, sid)
        main.post { onWakeUp?.invoke() }
        startAnswered(c)
    }

    /**
     * Auto-answer setting: ring the bell once so the room hears that a call is coming in, then
     * answer by itself. The camera, microphone and call audio only start once the bell has
     * finished: switching to call audio mid-bell cut the bell short, and an open microphone would
     * send the bell to the other tablet.
     */
    private fun autoAnswer(c: Conn, peer: Peer, sid: String) {
        takeIncoming(c, peer, sid)
        val id = callId
        Log.i(TAG, "incoming call from ${peer.name}, auto-answering after the bell")
        setState(CallState.AUTO_ANSWERING)
        c.send(msg("ringing"))
        main.post { onWakeUp?.invoke() }
        val answerNow = Runnable {
            exec.execute {
                val cc = conn
                if (callId == id && state == CallState.AUTO_ANSWERING && cc != null) startAnswered(cc, chime = false)
            }
        }
        playBell(onDone = answerNow)
        // In case the player never reports that it finished.
        timers.schedule(answerNow, BELL_FALLBACK_MS, TimeUnit.MILLISECONDS)
    }

    /** Ring here and wait for someone to press Answer or Decline. */
    private fun ring(c: Conn, peer: Peer, sid: String) {
        takeIncoming(c, peer, sid)
        val id = callId
        Log.i(TAG, "incoming call from ${peer.name}, ringing")
        setState(CallState.INCOMING)
        c.send(msg("ringing"))
        main.post { onWakeUp?.invoke() }
        ringTick(id)
        val gen = ++timeoutGen
        timers.schedule({
            exec.execute {
                if (callId == id && timeoutGen == gen && state == CallState.INCOMING) {
                    Log.i(TAG, "nobody answered")
                    conn?.send(msg("noanswer"))
                    endCall(ctx.getString(R.string.missed_call, callPeerName), notifyPeer = false)
                }
            }
        }, RING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun ringTick(id: Int) {
        if (callId != id || state != CallState.INCOMING) return
        playBell()
        timers.schedule({ exec.execute { ringTick(id) } }, RING_REPEAT_MS, TimeUnit.MILLISECONDS)
    }

    private fun startAnswered(c: Conn, chime: Boolean = true) {
        val id = callId
        setState(CallState.CONNECTING)
        c.send(msg("ok"))
        if (chime) chime()
        if (!startMedia()) return
        armTimeout(id, CONNECT_TIMEOUT_MS)
    }

    private fun onCallMessage(m: JSONObject) {
        val p = pc
        when (m.optString("t")) {
            "ringing" -> if (state == CallState.DIALING) {
                setState(CallState.RINGING)
                // Give them time to walk to the tablet; the callee gives up first.
                armTimeout(callId, RING_TIMEOUT_MS + CONNECT_TIMEOUT_MS)
            }
            "decline" -> endNotAnswered(R.string.err_declined, notifyPeer = false)
            "noanswer" -> endNotAnswered(R.string.err_no_answer, notifyPeer = false)
            "ok" -> if (state == CallState.DIALING || state == CallState.RINGING) {
                setState(CallState.CONNECTING)
                armTimeout(callId, CONNECT_TIMEOUT_MS)
                if (startMedia()) createOffer()
            }
            "busy" -> endCall(ctx.getString(R.string.err_busy, callPeerName), notifyPeer = false)
            "offer" -> if (p != null) setRemote(p, SessionDescription.Type.OFFER, m.getString("sdp")) { createAnswer() }
            "answer" -> if (p != null) setRemote(p, SessionDescription.Type.ANSWER, m.getString("sdp")) {}
            "ice" -> {
                val cand = IceCandidate(m.getString("mid"), m.getInt("idx"), m.getString("sdp"))
                if (remoteDescSet) p?.addIceCandidate(cand) else pendingIce.add(cand)
            }
            "bye" -> {
                Log.i(TAG, "other tablet hung up")
                endCall(missedCallInfo(), notifyPeer = false)
            }
            "resumed" -> if (state == CallState.RECONNECTING) {
                if (iceConnected()) {
                    Log.i(TAG, "signalling resumed, media never stopped")
                    setState(CallState.IN_CALL)
                } else {
                    Log.i(TAG, "signalling resumed, restarting ICE")
                    createOffer(iceRestart = true)
                }
            }
            "gone" -> endCall(ctx.getString(R.string.err_lost), notifyPeer = false)
        }
    }

    private fun onClosed(c: Conn) {
        val p = pendingPair
        if (p != null && c === p.c) {
            // The other tablet cancelled its pairing request.
            pendingPair = null
            pairRequest = null
            main.post { listener?.onPairRequestEnded() }
            return
        }
        if (c !== conn) return
        if (state == CallState.IN_CALL || state == CallState.RECONNECTING) {
            // Network hiccup: keep the call, try to get the signalling back.
            Log.i(TAG, "signalling socket lost during call")
            conn = null
            startReconnecting()
            return
        }
        endCall(missedCallInfo() ?: ctx.getString(R.string.err_not_connected), notifyPeer = false)
    }

    /** If the caller gave up while it was still ringing here, tell the room they missed a call. */
    private fun missedCallInfo() =
        if (state == CallState.INCOMING) ctx.getString(R.string.missed_call, callPeerName) else null

    // ---------------------------------------------------------------- reconnecting

    private fun startReconnecting() {
        if (state == CallState.RECONNECTING) return
        Log.i(TAG, "reconnecting (caller=$isCaller)")
        setState(CallState.RECONNECTING)
        reconnectDeadline = System.currentTimeMillis() + RECONNECT_GRACE_MS
        lastIceRestart = System.currentTimeMillis() - ICE_RESTART_INTERVAL_MS / 2
        scheduleReconnectTick(callId)
    }

    private fun scheduleReconnectTick(id: Int) {
        timers.schedule({ exec.execute { reconnectTick(id) } }, RECONNECT_TICK_MS, TimeUnit.MILLISECONDS)
    }

    private fun reconnectTick(id: Int) {
        if (callId != id || state != CallState.RECONNECTING) return
        val now = System.currentTimeMillis()
        // Audio/video still flowing (only the signalling socket died): no reason to give up.
        if (iceConnected()) reconnectDeadline = now + RECONNECT_GRACE_MS
        if (now > reconnectDeadline) {
            endCall(ctx.getString(R.string.err_lost))
            return
        }
        // Only the caller drives recovery, so the two tablets never restart at the same time.
        if (isCaller) {
            if (conn == null) {
                tryResume(id)
            } else if (now - lastIceRestart >= ICE_RESTART_INTERVAL_MS) {
                createOffer(iceRestart = true)
            }
        }
        scheduleReconnectTick(id)
    }

    /** Caller side: open a new signalling socket to the callee and ask to continue the call. */
    private fun tryResume(id: Int) {
        if (resumeInFlight) return
        val peer = callPeer?.let { Peers.get(it.id) ?: it } ?: return
        resumeInFlight = true
        timers.execute {
            val r = Link.open(prefs, peer, 2500)
            exec.execute {
                resumeInFlight = false
                val link = (r as? Link.Result.Ok)?.link ?: return@execute
                if (callId != id || state != CallState.RECONNECTING || conn != null) {
                    link.close()
                    return@execute
                }
                Log.i(TAG, "signalling socket re-opened, resuming")
                val c = newConn(link.socket, link.reader)
                c.session = link.session
                c.peerId = peer.id
                conn = c
                c.start()
                c.send(msg("resume").put("sid", sessionId))
            }
        }
    }

    /** Callee side: the caller re-opened the signalling socket after a network hiccup. */
    private fun onResumeRequest(c: Conn, peer: Peer, sid: String) {
        val active = state == CallState.IN_CALL || state == CallState.RECONNECTING
        if (active && !isCaller && callPeer?.id == peer.id && sid.isNotEmpty() && sid == sessionId) {
            Log.i(TAG, "caller resumed the call")
            val old = conn
            conn = c
            old?.close()
            c.send(msg("resumed"))
            if (state == CallState.RECONNECTING && iceConnected()) setState(CallState.IN_CALL)
        } else {
            c.send(msg("gone"))
            c.close()
        }
    }

    private fun iceConnected() = iceState == PeerConnection.IceConnectionState.CONNECTED ||
        iceState == PeerConnection.IceConnectionState.COMPLETED

    // ---------------------------------------------------------------- bell

    private fun onBellReceived(peer: Peer) {
        if (state != CallState.IDLE) return // already talking
        val from = peer.name
        Log.i(TAG, "bell from $from")
        lastBell = from to System.currentTimeMillis()
        setOnline(peer.id, true) // they just reached us
        playBell()
        main.post {
            onWakeUp?.invoke()
            listener?.onBell(from)
        }
    }

    /** Players that are still playing; kept here so they aren't garbage-collected mid-sound. */
    private val bellPlayers = java.util.Collections.synchronizedSet(HashSet<MediaPlayer>())

    /** Plays the ding-dong; [onDone] runs when it has finished (or failed to play). */
    private fun playBell(onDone: Runnable? = null) {
        try {
            val afd = ctx.resources.openRawResourceFd(R.raw.bell)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            val v = prefs.bellVolume.coerceIn(0, 100) / 100f
            mp.setVolume(v, v)
            val finished = { p: MediaPlayer ->
                bellPlayers.remove(p)
                p.release()
                onDone?.run()
            }
            mp.setOnCompletionListener { finished(it) }
            mp.setOnErrorListener { p, _, _ -> finished(p); true }
            mp.prepare()
            bellPlayers.add(mp)
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "bell sound failed", e)
            onDone?.run()
        }
    }

    private fun armTimeout(id: Int, ms: Long) {
        val gen = ++timeoutGen
        timers.schedule({
            exec.execute {
                val waiting = state == CallState.DIALING || state == CallState.RINGING ||
                    state == CallState.CONNECTING
                if (callId == id && timeoutGen == gen && waiting) {
                    if (state == CallState.RINGING) endNotAnswered(R.string.err_no_answer, notifyPeer = true)
                    else endCall(ctx.getString(R.string.err_timeout, callPeerName))
                }
            }
        }, ms, TimeUnit.MILLISECONDS)
    }

    private fun endNotAnswered(infoRes: Int, notifyPeer: Boolean) {
        val info = ctx.getString(infoRes, callPeerName)
        endCall(null, notifyPeer)
        main.post { listener?.onCallNotAnswered(info) }
    }

    private fun endCall(info: String?, notifyPeer: Boolean = true) {
        Log.i(TAG, "endCall state=$state reason=$info notifyPeer=$notifyPeer")
        callId++
        conn?.let { c ->
            conn = null
            if (notifyPeer) c.send(msg("bye"))
            c.close()
        }
        teardownMedia()
        setState(CallState.IDLE, info)
        callPeer = null
    }

    // ---------------------------------------------------------------- WebRTC

    private fun startMedia(): Boolean {
        val id = callId
        try {
            val useHw = prefs.hwEcho
            val audioModule = JavaAudioDeviceModule.builder(ctx)
                .setUseHardwareAcousticEchoCanceler(useHw && JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                .setUseHardwareNoiseSuppressor(useHw && JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .createAudioDeviceModule()
            adm = audioModule
            val f = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
            factory = f

            val enumerator: CameraEnumerator =
                if (Camera2Enumerator.isSupported(ctx)) Camera2Enumerator(ctx) else Camera1Enumerator(true)
            val camName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            val helper = SurfaceTextureHelper.create("intercom-capture", eglBase.eglBaseContext)
            surfaceHelper = helper
            val vs = f.createVideoSource(false)
            videoSource = vs
            if (camName != null) {
                val cap = enumerator.createCapturer(camName, null)
                capturer = cap
                cap.initialize(helper, ctx, vs.capturerObserver)
                if (prefs.hdVideo) cap.startCapture(1280, 720, 30) else cap.startCapture(640, 480, 30)
            }
            val vt = f.createVideoTrack("video0", vs)
            vt.addSink(localSink)
            localVideo = vt
            val asrc = f.createAudioSource(MediaConstraints())
            audioSource = asrc
            val at = f.createAudioTrack("audio0", asrc)

            // Same LAN: no STUN/TURN servers needed, host candidates are enough.
            val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                // Keep gathering candidates so a reconnected Wi-Fi is picked up.
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val p = f.createPeerConnection(cfg, PcObserver(id)) ?: error("createPeerConnection failed")
            pc = p
            p.addTrack(at, listOf("s0"))
            p.addTrack(vt, listOf("s0"))
            startCallAudio()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "startMedia failed", e)
            endCall(ctx.getString(R.string.err_media))
            return false
        }
    }

    private fun teardownMedia() {
        stopCallAudio()
        localVideo?.removeSink(localSink)
        localVideo = null
        pc?.dispose() // also disposes the tracks
        pc = null
        try {
            capturer?.stopCapture()
        } catch (_: InterruptedException) {
        }
        capturer?.dispose(); capturer = null
        videoSource?.dispose(); videoSource = null
        audioSource?.dispose(); audioSource = null
        surfaceHelper?.dispose(); surfaceHelper = null
        factory?.dispose(); factory = null
        adm?.release(); adm = null
        remoteDescSet = false
        pendingIce.clear()
        iceState = null
    }

    private fun createOffer(iceRestart: Boolean = false) {
        val p = pc ?: return
        val id = callId
        if (iceRestart) lastIceRestart = System.currentTimeMillis()
        val constraints = MediaConstraints()
        if (iceRestart) constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        p.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) = exec.execute {
                if (id != callId) return@execute
                p.setLocalDescription(SdpAdapter(), sdp)
                conn?.send(msg("offer").put("sdp", sdp.description))
            }
        }, constraints)
    }

    private fun createAnswer() {
        val p = pc ?: return
        val id = callId
        p.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) = exec.execute {
                if (id != callId) return@execute
                p.setLocalDescription(SdpAdapter(), sdp)
                conn?.send(msg("answer").put("sdp", sdp.description))
            }
        }, MediaConstraints())
    }

    private fun setRemote(p: PeerConnection, type: SessionDescription.Type, sdp: String, then: () -> Unit) {
        val id = callId
        p.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() = exec.execute {
                if (id != callId) return@execute
                remoteDescSet = true
                pendingIce.forEach { p.addIceCandidate(it) }
                pendingIce.clear()
                then()
            }
        }, SessionDescription(type, sdp))
    }

    private fun onIce(s: PeerConnection.IceConnectionState) {
        iceState = s
        Log.i(TAG, "ICE $s (state=$state)")
        when (s) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> when (state) {
                CallState.CONNECTING -> {
                    setState(CallState.IN_CALL)
                    armMaxDuration()
                }
                CallState.RECONNECTING -> {
                    Log.i(TAG, "reconnected")
                    setState(CallState.IN_CALL)
                }
                else -> {}
            }
            PeerConnection.IceConnectionState.FAILED -> when (state) {
                CallState.IN_CALL -> startReconnecting()
                CallState.RECONNECTING -> {} // the reconnect tick will try an ICE restart
                else -> endCall(ctx.getString(R.string.err_lost))
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> if (state == CallState.IN_CALL) {
                // Often recovers by itself within a second or two; only then show "Reconnecting".
                val id = callId
                timers.schedule({
                    exec.execute {
                        if (callId == id && state == CallState.IN_CALL &&
                            (iceState == PeerConnection.IceConnectionState.DISCONNECTED ||
                                iceState == PeerConnection.IceConnectionState.FAILED)
                        ) startReconnecting()
                    }
                }, 2, TimeUnit.SECONDS)
            }
            else -> {}
        }
    }

    private fun armMaxDuration() {
        val minutes = prefs.maxCallMinutes
        if (minutes <= 0) return
        val id = callId
        timers.schedule({
            exec.execute {
                if (callId == id && state != CallState.IDLE) {
                    endCall(ctx.getString(R.string.auto_ended, minutes))
                }
            }
        }, minutes.toLong(), TimeUnit.MINUTES)
    }

    private class PcObserver(val id: Int) : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) = exec.execute {
            if (id == callId) {
                conn?.send(msg("ice").put("mid", c.sdpMid).put("idx", c.sdpMLineIndex).put("sdp", c.sdp))
            }
        }

        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = exec.execute {
            if (id == callId) onIce(s)
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.addSink(remoteSink)
        }

        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "SDP create failed: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.e(TAG, "SDP set failed: $error")
        }
    }

    // ---------------------------------------------------------------- audio

    private fun audioManager() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun startCallAudio() {
        val am = audioManager()
        savedAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
        am.isMicrophoneMute = false
        val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (max * prefs.callVolume / 100).coerceIn(1, max), 0)
        audioActive = true
    }

    private fun stopCallAudio() {
        if (!audioActive) return
        audioActive = false
        val am = audioManager()
        am.isSpeakerphoneOn = false
        am.mode = savedAudioMode
    }

    /** Short "ding-ding" so people in the room know the camera just went live. */
    private fun chime() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
            timers.schedule({ tg.release() }, 2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "chime failed", e)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun msg(type: String) = JSONObject().put("t", type)

    private fun setState(s: CallState, info: String? = null) {
        state = s
        val peer = callPeerName
        main.post { listener?.onCallState(s, peer, info) }
    }

    private fun notifyInfo(info: String) {
        val s = state
        val peer = callPeerName
        main.post { listener?.onCallState(s, peer, info) }
    }

    private fun setOnline(peerId: String, isOnline: Boolean) {
        if (isOnline) lastSeen[peerId] = System.currentTimeMillis()
        if (online.put(peerId, isOnline) != isOnline) roomsChanged()
    }

    private fun roomsChanged() {
        main.post { listener?.onRoomsChanged() }
    }
}
