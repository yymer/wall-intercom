package app.wallintercom

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * "Share → Wall Intercom" from any app (Google Photos, Files, the browser, …): copies the shared
 * pictures into the app's own album, so nobody has to make folders or use a file manager.
 */
class ShareActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var text: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: LinearLayout
    private var uris: List<Uri> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setFinishOnTouchOutside(false)
        val pad = dp(24)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(12))
        }
        progress = ProgressBar(this).also { col.addView(it) }
        text = TextView(this).apply {
            textSize = 18f
            setPadding(0, dp(12), 0, dp(12))
        }.also { col.addView(it) }
        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }.also { col.addView(it) }
        setContentView(col)

        uris = sharedUris(intent)
        if (uris.isEmpty()) {
            finishWith(getString(R.string.share_nothing))
            return
        }
        text.text = resources.getQuantityString(R.plurals.share_adding, uris.size, uris.size)

        // Android 6–9 need the storage permission to write to Pictures/. (Android 5 grants it at
        // install, Android 10+ doesn't need it for our own album.)
        val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT in 23..28 && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), REQ_WRITE)
        } else {
            startImport()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode != REQ_WRITE) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startImport()
        else finishWith(getString(R.string.share_need_permission))
    }

    private fun sharedUris(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            @Suppress("DEPRECATION") (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        )
        Intent.ACTION_SEND_MULTIPLE -> @Suppress("DEPRECATION")
        i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.filterNotNull() ?: emptyList()
        else -> emptyList()
    }

    private fun startImport() = Thread {
        var added = 0
        var already = 0
        var failed = 0
        var album: Pair<String, String>? = null
        for (u in uris) {
            val r = Albums.import(this, u)
            val uri = when (r) {
                is Albums.Import.Added -> { added++; r.uri }
                is Albums.Import.AlreadyThere -> { already++; r.uri }
                Albums.Import.Failed -> { failed++; null }
            }
            if (album == null && uri != null) album = Albums.albumOf(contentResolver, uri)
        }
        runOnUiThread { if (!isFinishing) done(added, already, failed, album) }
    }.start()

    private fun done(added: Int, already: Int, failed: Int, album: Pair<String, String>?) {
        progress.visibility = View.GONE
        val lines = mutableListOf<String>()
        if (added > 0) lines += resources.getQuantityString(R.plurals.share_added, added, added, Albums.OWN_ALBUM)
        if (already > 0) lines += resources.getQuantityString(R.plurals.share_already, already, already)
        if (failed > 0) lines += resources.getQuantityString(R.plurals.share_failed, failed, failed)

        var offerSwitch = false
        if (album != null) {
            val (id, name) = album
            when {
                prefs.albumId == id -> lines += getString(R.string.share_in_slideshow)
                prefs.albumId == null && prefs.folderUri == null -> {
                    // Nothing chosen yet: this is obviously what they want to see.
                    prefs.albumId = id
                    prefs.albumName = name
                    lines += getString(R.string.share_now_showing)
                }
                else -> offerSwitch = true
            }
        }
        text.text = lines.joinToString("\n\n")

        if (offerSwitch) {
            addButton(R.string.share_show_album) {
                val (id, name) = album!!
                prefs.albumId = id
                prefs.albumName = name
                prefs.folderUri = null
                finish()
            }
        }
        addButton(R.string.ok) { finish() }
    }

    private fun finishWith(message: String) {
        progress.visibility = View.GONE
        text.text = message
        addButton(R.string.ok) { finish() }
    }

    private fun addButton(textRes: Int, onClick: () -> Unit) {
        buttons.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(textRes)
            setOnClickListener { onClick() }
        })
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val REQ_WRITE = 1
    }
}
