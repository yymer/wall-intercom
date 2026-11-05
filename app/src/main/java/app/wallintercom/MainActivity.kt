package app.wallintercom

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity(), CallManager.Listener {
    private lateinit var prefs: Prefs
    private lateinit var imgA: ImageView
    private lateinit var imgB: ImageView
    private lateinit var front: ImageView
    private lateinit var hint: TextView
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var overlay: View
    private lateinit var callBtn: Button
    private lateinit var bellBtn: Button
    private lateinit var peerStatus: TextView
    private lateinit var callResult: TextView
    private lateinit var callPanel: View
    private lateinit var callStatus: TextView
    private lateinit var answerBtn: Button
    private lateinit var endBtn: Button
    private lateinit var message: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var images: List<Uri> = emptyList()
    private var slideIndex = 0
    private var resumed = false

    private val slideRunnable = Runnable { nextSlide() }
    private val hideOverlayRunnable = Runnable { hideOverlay() }
    private val hideMessageRunnable = Runnable { message.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        CallManager.init(this)
        IntercomService.start(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        imgA = findViewById(R.id.imgA)
        imgB = findViewById(R.id.imgB)
        front = imgA
        hint = findViewById(R.id.hint)
        remoteView = findViewById(R.id.remoteView)
        localView = findViewById(R.id.localView)
        overlay = findViewById(R.id.overlay)
        callBtn = findViewById(R.id.callBtn)
        bellBtn = findViewById(R.id.bellBtn)
        peerStatus = findViewById(R.id.peerStatus)
        callResult = findViewById(R.id.callResult)
        callPanel = findViewById(R.id.callPanel)
        callStatus = findViewById(R.id.callStatus)
        answerBtn = findViewById(R.id.answerBtn)
        endBtn = findViewById(R.id.endBtn)
        message = findViewById(R.id.message)

        val egl = CallManager.eglBase.eglBaseContext
        remoteView.init(egl, null)
        remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        remoteView.setEnableHardwareScaler(true)
        localView.init(egl, null)
        localView.setZOrderMediaOverlay(true)
        localView.setMirror(true)
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        CallManager.remoteSink.target = remoteView
        CallManager.localSink.target = localView

        findViewById<View>(R.id.root).setOnClickListener { onScreenTouched() }
        overlay.setOnClickListener { hideOverlay() }
        callBtn.setOnClickListener {
            hideOverlay()
            CallManager.dial()
        }
        bellBtn.setOnClickListener {
            // Buttons stay up; the bell button shows it's ringing until the bell has played.
            setBellRinging(true)
            showOverlay() // restart the auto-hide timer
            CallManager.sendBell { ok ->
                if (ok) handler.postDelayed({ setBellRinging(false) }, BELL_SOUND_MS)
                else setBellRinging(false)
            }
        }
        answerBtn.setOnClickListener { CallManager.answer() }
        endBtn.setOnClickListener { CallManager.hangup() } // declines while ringing
        val settingsBtn = findViewById<View>(R.id.settingsBtn)
        settingsBtn.setOnClickListener { showMessage(getString(R.string.hold_for_settings)) }
        settingsBtn.setOnLongClickListener {
            hideOverlay()
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemUi()
        lockTaskIfDeviceOwner()
        CallManager.listener = this
        onCallState(CallManager.state, prefs.peerName, null)
        updateCallButton()
        // A bell that arrived while we were in the background (or the screen was off).
        CallManager.lastBell?.let { (from, at) ->
            if (at > bellShownAt && System.currentTimeMillis() - at < 60_000) onBell(from)
        }
        // Re-read the folder every time we come back (settings may have changed).
        images = emptyList()
        slideIndex = 0
        nextSlide()
    }

    override fun onPause() {
        resumed = false
        CallManager.listener = null
        handler.removeCallbacks(slideRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        CallManager.remoteSink.target = null
        CallManager.localSink.target = null
        remoteView.release()
        localView.release()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // This is the home screen: Back does nothing except close the overlay.
        hideOverlay()
    }

    // ---------------------------------------------------------------- call UI

    override fun onCallState(state: CallState, peer: String, info: String?) {
        val inCall = state != CallState.IDLE
        val ringingHere = state == CallState.INCOMING
        // No video views while it's only ringing: nothing is being captured or shown yet.
        val videoVis = if (inCall && !ringingHere) View.VISIBLE else View.GONE
        remoteView.visibility = videoVis
        localView.visibility = videoVis
        callPanel.visibility = if (inCall) View.VISIBLE else View.GONE
        answerBtn.visibility = if (ringingHere) View.VISIBLE else View.GONE
        endBtn.setText(if (ringingHere) R.string.decline else R.string.end_call)
        callStatus.text = when (state) {
            CallState.DIALING -> getString(R.string.state_calling, peer)
            CallState.RINGING -> getString(R.string.state_ringing, peer)
            CallState.INCOMING -> getString(R.string.state_incoming)
            CallState.CONNECTING -> getString(R.string.state_connecting, peer)
            CallState.IN_CALL -> peer
            CallState.RECONNECTING -> getString(R.string.state_reconnecting)
            CallState.IDLE -> ""
        }
        if (inCall) {
            hideOverlay()
            setBrightness(true)
        } else {
            remoteView.clearImage()
            localView.clearImage()
            if (overlay.visibility != View.VISIBLE) setBrightness(false)
        }
        if (info != null) {
            // A missed call stays up longer so whoever walks by can call back.
            val missed = info == getString(R.string.missed_call, peer)
            showMessage(info, if (missed) 60_000 else 5_000)
        }
    }

    override fun onPeerStatus(online: Boolean, peer: String) = updateCallButton()

    private var bellShownAt = 0L

    override fun onBell(from: String) {
        bellShownAt = System.currentTimeMillis()
        if (CallManager.state != CallState.IDLE) return
        showOverlay() // so they can call back right away
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, 60_000)
        showMessage(getString(R.string.bell_from, from), 60_000)
    }

    override fun onCallNotAnswered(info: String) {
        if (CallManager.state != CallState.IDLE) return
        showOverlay() // so they can try again or ring the bell
        callResult.text = info
        callResult.visibility = View.VISIBLE
    }

    private fun updateCallButton() {
        callBtn.setText(R.string.call_button)
        bellBtn.setText(R.string.bell_button)
        if (CallManager.peerOnline) {
            peerStatus.setText(R.string.status_online)
            peerStatus.setTextColor(Color.parseColor("#7CE38B"))
        } else {
            peerStatus.setText(R.string.status_offline)
            peerStatus.setTextColor(Color.parseColor("#BBBBBB"))
        }
    }

    private fun onScreenTouched() {
        if (CallManager.state != CallState.IDLE) return
        if (overlay.visibility == View.VISIBLE) hideOverlay() else showOverlay()
    }

    private fun setBellRinging(ringing: Boolean) {
        bellBtn.isEnabled = !ringing
        bellBtn.setBackgroundResource(if (ringing) R.drawable.bg_bell_ringing else R.drawable.bg_bell)
    }

    private fun showOverlay() {
        updateCallButton()
        overlay.visibility = View.VISIBLE
        hint.alpha = 0f // don't let the "no photos" hint show through the overlay
        setBrightness(true)
        CallManager.pingNow()
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, OVERLAY_TIMEOUT_MS)
    }

    private fun hideOverlay() {
        handler.removeCallbacks(hideOverlayRunnable)
        if (message.visibility == View.VISIBLE) message.visibility = View.GONE
        overlay.visibility = View.GONE
        callResult.visibility = View.GONE
        hint.alpha = 1f
        if (CallManager.state == CallState.IDLE) setBrightness(false)
    }

    private fun showMessage(text: String, durationMs: Long = 5_000) {
        message.text = text
        message.visibility = View.VISIBLE
        handler.removeCallbacks(hideMessageRunnable)
        handler.postDelayed(hideMessageRunnable, durationMs)
    }

    // ---------------------------------------------------------------- screen

    private fun setBrightness(active: Boolean) {
        val lp = window.attributes
        lp.screenBrightness = if (active) 1f else {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val pct = if (prefs.isNight(hour)) prefs.nightBrightness else prefs.idleBrightness
            pct.coerceIn(0, 100) / 100f
        }
        window.attributes = lp
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    /** Only does anything if the app was made device owner via adb (optional, see README). */
    private fun lockTaskIfDeviceOwner() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val admin = ComponentName(this, AdminReceiver::class.java)
        dpm.setLockTaskPackages(admin, arrayOf(packageName))
        dpm.setKeyguardDisabled(admin, true)
        val am = getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
    }

    // ---------------------------------------------------------------- slideshow

    private fun nextSlide() {
        handler.removeCallbacks(slideRunnable)
        if (!resumed) return
        if (overlay.visibility != View.VISIBLE && CallManager.state == CallState.IDLE) setBrightness(false)
        if (slideIndex >= images.size) {
            // End of a round (or first start): re-scan the folder so new photos show up, reshuffle.
            reloadFolder {
                if (images.isEmpty()) {
                    showHint()
                    handler.postDelayed(slideRunnable, 30_000)
                } else {
                    showSlide()
                }
            }
            return
        }
        showSlide()
    }

    private fun showHint() {
        hint.text = if (prefs.folderUri == null) {
            getString(R.string.no_folder)
        } else {
            getString(R.string.no_pictures)
        }
        hint.visibility = View.VISIBLE
    }

    private fun reloadFolder(then: () -> Unit) {
        val uri = prefs.folderUri
        io.execute {
            val list = uri?.let { listImages(Uri.parse(it)) } ?: emptyList()
            handler.post {
                images = list.shuffled()
                slideIndex = 0
                then()
            }
        }
    }

    private fun listImages(tree: Uri): List<Uri> {
        val out = ArrayList<Uri>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(1) ?: ""
                    if (mime.startsWith("image/")) {
                        out += DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("Slideshow", "can't list folder", e)
        }
        return out
    }

    private fun showSlide() {
        hint.visibility = View.GONE
        val uri = images[slideIndex++]
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        io.execute {
            val bmp = decode(uri, max(w, h))
            handler.post {
                if (!resumed) return@post
                if (bmp != null) crossfade(bmp)
                val delay = if (bmp != null) prefs.slideSeconds.coerceAtLeast(5) * 1000L else 500L
                handler.postDelayed(slideRunnable, delay)
            }
        }
    }

    private fun crossfade(bmp: Bitmap) {
        val incoming = if (front === imgA) imgB else imgA
        val outgoing = front
        incoming.scaleType = if (prefs.fillScreen) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.setImageBitmap(bmp)
        incoming.alpha = 0f
        incoming.animate().alpha(1f).setDuration(1500).start()
        outgoing.animate().alpha(0f).setDuration(1500).withEndAction { outgoing.setImageDrawable(null) }.start()
        front = incoming
    }

    /** Decodes an image downsampled to roughly screen size, rotated according to EXIF. */
    private fun decode(uri: Uri, targetLongSide: Int): Bitmap? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetLongSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            val orientation = try {
                contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) return bmp
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
            if (rotated !== bmp) bmp.recycle()
            return rotated
        } catch (e: Throwable) { // includes OutOfMemoryError on huge images
            Log.w("Slideshow", "can't decode $uri", e)
            return null
        }
    }

    companion object {
        /** How long the Call/Bell buttons stay up after the last touch. */
        private const val OVERLAY_TIMEOUT_MS = 25_000L
        /** Roughly the length of the ding-dong; the bell button stays "ringing" this long. */
        private const val BELL_SOUND_MS = 2_600L
    }
}
