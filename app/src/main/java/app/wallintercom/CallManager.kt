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

enum class CallState { IDLE, DIALING, CONNECTING, IN_CALL, RECONNECTING }

/** Forwards video frames to whatever renderer is currently attached. */
class ProxyVideoSink : VideoSink {
    @Volatile
    var target: VideoSink? = null
    override fun onFrame(frame: VideoFrame) {
        target?.onFrame(frame)
    }
}

/**
 * Peer-to-peer intercom between two tablets.
 *
 * Signalling: each tablet listens on a TCP port. The caller connects to the other tablet's IP
 * and the two exchange one-line JSON messages:
 *   caller -> hello{name,sid}    callee -> ok{name} (auto-answer) | busy
 *   caller -> offer{sdp}         callee -> answer{sdp}
 *   both   -> ice{mid,idx,sdp}   either -> bye
 *   caller -> resume{name,sid}   callee -> resumed | gone   (new socket after the old one died)
 *   ping{name} -> pong{name}     (separate short connection, for the "online" indicator)
 *   bell{name} -> bellok         (separate short connection, rings the bell on the other tablet)
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

    interface Listener {
        fun onCallState(state: CallState, peer: String, info: String?)
        fun onPeerStatus(online: Boolean, peer: String)
        fun onBell(from: String)
    }

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
    /** Called on the main thread on an incoming call or bell (wake screen + show activity). */
    var onWakeUp: (() -> Unit)? = null

    /** Last bell received: (from, time). The activity shows it when it comes to the front. */
    @Volatile
    var lastBell: Pair<String, Long>? = null
        private set

    @Volatile
    var state = CallState.IDLE
        private set
    @Volatile
    var peerOnline = false
        private set

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

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        ctx = context.applicationContext
        prefs = Prefs(ctx)
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions()
        )
        thread(name = "intercom-listener", isDaemon = true) { listenLoop() }
        timers.scheduleWithFixedDelay({ pingPeer() }, 1, 20, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- public API

    fun dial() = exec.execute {
        if (state != CallState.IDLE) return@execute
        val ip = prefs.peerIp.trim()
        if (ip.isEmpty()) {
            notifyInfo(ctx.getString(R.string.err_no_ip))
            return@execute
        }
        val id = ++callId
        setState(CallState.DIALING)
        val socket = try {
            Socket().apply {
                connect(InetSocketAddress(ip, prefs.port), 4000)
                tcpNoDelay = true
                keepAlive = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            setState(CallState.IDLE, ctx.getString(R.string.err_unreachable, prefs.peerName))
            setPeer(false)
            return@execute
        }
        val c = newConn(socket)
        conn = c
        isCaller = true
        sessionId = UUID.randomUUID().toString()
        c.start()
        c.send(msg("hello").put("name", prefs.myName).put("sid", sessionId))
        armTimeout(id)
    }

    fun hangup() = exec.execute {
        Log.i(TAG, "End call pressed")
        if (state != CallState.IDLE) endCall(null)
    }

    fun pingNow() = timers.execute { pingPeer() }

    /**
     * Rings the bell on the other tablet. [onDone] is called on the main thread with whether the
     * other tablet received it; failures are also shown as an info message.
     */
    fun sendBell(onDone: (Boolean) -> Unit) = timers.execute {
        val ip = prefs.peerIp.trim()
        if (ip.isEmpty()) {
            notifyInfo(ctx.getString(R.string.err_no_ip))
            main.post { onDone(false) }
            return@execute
        }
        val ok = try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, prefs.port), 3000)
                s.soTimeout = 3000
                val out = s.getOutputStream()
                out.write((msg("bell").put("name", prefs.myName).toString() + "\n").toByteArray())
                out.flush()
                JSONObject(s.getInputStream().bufferedReader().readLine() ?: "{}").optString("t") == "bellok"
            }
        } catch (e: Exception) {
            Log.w(TAG, "bell failed: ${e.message}")
            false
        }
        setPeer(ok)
        if (!ok) notifyInfo(ctx.getString(R.string.err_unreachable, prefs.peerName))
        main.post { onDone(ok) }
    }

    /** Re-bind the listening socket (e.g. after the port changed). */
    fun restartListener() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
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

    private fun newConn(s: Socket) = Conn(
        s,
        onMessage = { c, m -> exec.execute { onMessage(c, m) } },
        onClosed = { c -> exec.execute { onClosed(c) } },
    )

    private fun pingPeer() {
        val ip = prefs.peerIp.trim()
        if (ip.isEmpty()) {
            setPeer(false); return
        }
        if (state != CallState.IDLE) {
            setPeer(true); return
        }
        val ok = try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, prefs.port), 2000)
                s.soTimeout = 3000
                val out = s.getOutputStream()
                out.write((msg("ping").put("name", prefs.myName).toString() + "\n").toByteArray())
                out.flush()
                val line = s.getInputStream().bufferedReader().readLine() ?: "{}"
                val m = JSONObject(line)
                if (m.optString("t") == "pong") {
                    m.optString("name").takeIf { it.isNotBlank() }?.let { prefs.peerName = it }
                    true
                } else false
            }
        } catch (_: Exception) {
            false
        }
        setPeer(ok)
    }

    private fun onMessage(c: Conn, m: JSONObject) {
        // Calls are auto-answered, so only ever talk to the configured other tablet.
        if (!isFromPeer(c)) {
            Log.w(TAG, "ignoring connection from ${c.remoteAddress?.hostAddress} (not the other tablet)")
            c.close()
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
                onBellReceived(m.optString("name").ifBlank { prefs.peerName })
            }
            "hello" -> onHello(c, m.optString("name"), m.optString("sid"))
            "resume" -> onResumeRequest(c, m.optString("sid"))
            else -> if (c === conn) onCallMessage(m)
        }
    }

    private fun isFromPeer(c: Conn): Boolean {
        val ip = prefs.peerIp.trim()
        if (ip.isEmpty()) return false
        return try {
            c.remoteAddress != null && c.remoteAddress == java.net.InetAddress.getByName(ip)
        } catch (_: Exception) {
            false
        }
    }

    private fun onHello(c: Conn, from: String, sid: String) {
        when {
            state == CallState.IDLE -> accept(c, from, sid)
            // The other tablet gave up on a broken call and is calling again: start over.
            state == CallState.RECONNECTING && from == prefs.peerName -> {
                endCall(null, notifyPeer = false)
                accept(c, from, sid)
            }
            // Both pressed "call" at the same moment: the tablet whose name sorts first keeps
            // its outgoing call, the other one drops its own and accepts.
            state == CallState.DIALING && prefs.myName > from -> {
                val old = conn
                conn = null
                old?.close()
                accept(c, from, sid)
            }
            else -> {
                c.send(msg("busy"))
                c.close()
            }
        }
    }

    private fun accept(c: Conn, from: String, sid: String) {
        conn = c
        isCaller = false
        sessionId = sid
        val id = ++callId
        if (from.isNotBlank()) prefs.peerName = from
        setState(CallState.CONNECTING)
        c.send(msg("ok").put("name", prefs.myName))
        main.post { onWakeUp?.invoke() }
        chime()
        if (!startMedia()) return
        armTimeout(id)
    }

    private fun onCallMessage(m: JSONObject) {
        val p = pc
        when (m.optString("t")) {
            "ok" -> if (state == CallState.DIALING) {
                m.optString("name").takeIf { it.isNotBlank() }?.let { prefs.peerName = it }
                setState(CallState.CONNECTING)
                if (startMedia()) createOffer()
            }
            "busy" -> endCall(ctx.getString(R.string.err_busy, prefs.peerName), notifyPeer = false)
            "offer" -> if (p != null) setRemote(p, SessionDescription.Type.OFFER, m.getString("sdp")) { createAnswer() }
            "answer" -> if (p != null) setRemote(p, SessionDescription.Type.ANSWER, m.getString("sdp")) {}
            "ice" -> {
                val cand = IceCandidate(m.getString("mid"), m.getInt("idx"), m.getString("sdp"))
                if (remoteDescSet) p?.addIceCandidate(cand) else pendingIce.add(cand)
            }
            "bye" -> {
                Log.i(TAG, "other tablet hung up")
                endCall(null, notifyPeer = false)
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
        if (c !== conn) return
        if (state == CallState.IN_CALL || state == CallState.RECONNECTING) {
            // Network hiccup: keep the call, try to get the signalling back.
            Log.i(TAG, "signalling socket lost during call")
            conn = null
            startReconnecting()
            return
        }
        endCall(ctx.getString(R.string.err_not_connected), notifyPeer = false)
    }

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
        resumeInFlight = true
        val ip = prefs.peerIp.trim()
        val port = prefs.port
        timers.execute {
            val s = try {
                Socket().apply {
                    connect(InetSocketAddress(ip, port), 2500)
                    tcpNoDelay = true
                    keepAlive = true
                }
            } catch (_: Exception) {
                null
            }
            exec.execute {
                resumeInFlight = false
                if (s == null) return@execute
                if (callId != id || state != CallState.RECONNECTING || conn != null) {
                    try { s.close() } catch (_: Exception) {}
                    return@execute
                }
                Log.i(TAG, "signalling socket re-opened, resuming")
                val c = newConn(s)
                conn = c
                c.start()
                c.send(msg("resume").put("name", prefs.myName).put("sid", sessionId))
            }
        }
    }

    /** Callee side: the caller re-opened the signalling socket after a network hiccup. */
    private fun onResumeRequest(c: Conn, sid: String) {
        val active = state == CallState.IN_CALL || state == CallState.RECONNECTING
        if (active && !isCaller && sid.isNotEmpty() && sid == sessionId) {
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

    private fun onBellReceived(from: String) {
        if (state != CallState.IDLE) return // already talking
        Log.i(TAG, "bell from $from")
        lastBell = from to System.currentTimeMillis()
        setPeer(true) // they just reached us, so they're online
        playBell()
        main.post {
            onWakeUp?.invoke()
            listener?.onBell(from)
        }
    }

    private fun playBell() {
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
            mp.setOnCompletionListener { it.release() }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "bell sound failed", e)
        }
    }

    private fun armTimeout(id: Int) {
        timers.schedule({
            exec.execute {
                if (callId == id && (state == CallState.DIALING || state == CallState.CONNECTING)) {
                    endCall(ctx.getString(R.string.err_timeout, prefs.peerName))
                }
            }
        }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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

    private fun startCallAudio() {
        val am = ctx.getSystemService(AudioManager::class.java)
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
        val am = ctx.getSystemService(AudioManager::class.java)
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
        val peer = prefs.peerName
        main.post { listener?.onCallState(s, peer, info) }
    }

    private fun notifyInfo(info: String) {
        val s = state
        val peer = prefs.peerName
        main.post { listener?.onCallState(s, peer, info) }
    }

    private fun setPeer(online: Boolean) {
        peerOnline = online
        val peer = prefs.peerName
        main.post { listener?.onPeerStatus(online, peer) }
    }
}
