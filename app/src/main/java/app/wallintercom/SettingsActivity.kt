package app.wallintercom

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private lateinit var sourceLabel: TextView
    private lateinit var roomsCol: LinearLayout
    private val savers = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        CallManager.init(this)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(16), dp(32), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        heading(R.string.h_this_tablet)
        label(getString(R.string.my_ip, localIps()), 18f)
        textField(R.string.my_name, prefs.myName) {
            prefs.myName = it.trim().ifEmpty { getString(R.string.default_my_name) }
        }
        checkBox(R.string.auto_start, prefs.autoStart) { prefs.autoStart = it }
        checkBox(R.string.use_as_home, Prefs.HomeScreen.isEnabled(this)) { Prefs.HomeScreen.setEnabled(this, it) }

        heading(R.string.h_rooms)
        roomsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(roomsCol)
        rebuildRooms()
        button(R.string.add_by_ip) { addByIp() }
        label(getString(R.string.add_by_ip_hint))
        intField(R.string.port, prefs.port, 1024..65535) { prefs.port = it }

        heading(R.string.h_slideshow)
        sourceLabel = label(sourceText(), 18f)
        label(getString(R.string.share_tip))
        button(R.string.choose_album) { chooseAlbum() }
        button(R.string.choose_folder) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    // Show internal storage right away (Android 5 hides it behind a menu).
                    .putExtra("android.content.extra.SHOW_ADVANCED", true),
                REQ_FOLDER,
            )
        }
        intField(R.string.slide_seconds, prefs.slideSeconds, 5..86400) { prefs.slideSeconds = it }
        checkBox(R.string.fill_screen, prefs.fillScreen) { prefs.fillScreen = it }

        heading(R.string.h_screen)
        intField(R.string.idle_brightness, prefs.idleBrightness, 0..100) { prefs.idleBrightness = it }
        intField(R.string.night_start, prefs.nightStart, 0..23) { prefs.nightStart = it }
        intField(R.string.night_end, prefs.nightEnd, 0..23) { prefs.nightEnd = it }
        intField(R.string.night_brightness, prefs.nightBrightness, 0..100) {
            prefs.nightBrightness = it
        }

        heading(R.string.h_calls)
        checkBox(R.string.auto_answer, prefs.autoAnswer) { prefs.autoAnswer = it }
        intField(R.string.call_volume, prefs.callVolume, 0..100) { prefs.callVolume = it }
        intField(R.string.bell_volume, prefs.bellVolume, 0..100) { prefs.bellVolume = it }
        intField(R.string.max_minutes, prefs.maxCallMinutes, 0..1440) {
            prefs.maxCallMinutes = it
        }
        checkBox(R.string.hd_video, prefs.hdVideo) { prefs.hdVideo = it }
        checkBox(R.string.hw_echo, prefs.hwEcho) { prefs.hwEcho = it }

        heading(R.string.h_permissions)
        // Android 5 has neither of these restrictions, so nothing to allow there.
        if (Build.VERSION.SDK_INT >= 23) {
            button(R.string.allow_overlay) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            button(R.string.allow_battery) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    toast(getString(R.string.already_allowed))
                } else {
                    @Suppress("BatteryLife")
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                }
            }
        }
        button(R.string.open_android_settings) {
            try {
                stopLockTask()
            } catch (_: Exception) {
            }
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(24), 0, 0) }
        row.addView(Button(this).apply {
            setText(R.string.save)
            textSize = 20f
            setOnClickListener { save() }
        })
        row.addView(Button(this).apply {
            setText(R.string.cancel)
            textSize = 20f
            setOnClickListener { finish() }
        })
        col.addView(row)
    }

    private fun save() {
        val oldPort = prefs.port
        savers.forEach { it() }
        if (prefs.port != oldPort) CallManager.restartListener()
        CallManager.pingNow()
        toast(getString(R.string.saved))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.folderUri = uri.toString()
            prefs.albumId = null
            prefs.albumName = null
            sourceLabel.text = sourceText()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode != REQ_READ) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) showAlbums()
        else toast(getString(R.string.need_photo_permission))
    }

    // ---------------------------------------------------------------- rooms

    private fun rebuildRooms() {
        roomsCol.removeAllViews()
        val peers = Peers.all()
        if (peers.isEmpty()) {
            roomsCol.addView(TextView(this).apply {
                setText(R.string.rooms_none)
                textSize = 16f
                setPadding(0, dp(10), 0, 0)
            })
            return
        }
        for (p in peers) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            row.addView(TextView(this).apply {
                text = p.name
                textSize = 20f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                setText(R.string.remove)
                isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setMessage(getString(R.string.remove_confirm, p.name))
                        .setPositiveButton(R.string.remove) { _, _ ->
                            CallManager.unpair(p.id)
                            rebuildRooms()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            })
            roomsCol.addView(row)
        }
    }

    private fun addByIp() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "192.168.1.23"
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_by_ip)
            .setMessage(R.string.ip_prompt)
            .setView(input)
            .setPositiveButton(R.string.pair) { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    PairingDialogs.pair(this, ip, prefs.port, null, onPaired = { rebuildRooms() })
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- albums

    private fun chooseAlbum() {
        val perm = Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), REQ_READ)
        } else {
            showAlbums()
        }
    }

    private fun showAlbums() {
        Thread {
            val albums = Albums.list(contentResolver)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (albums.isEmpty()) {
                    toast(getString(R.string.no_albums))
                    return@runOnUiThread
                }
                val adapter = AlbumAdapter(albums)
                AlertDialog.Builder(this)
                    .setTitle(R.string.choose_album)
                    .setAdapter(adapter) { _, which ->
                        val a = albums[which]
                        prefs.albumId = a.id
                        prefs.albumName = a.name
                        prefs.folderUri = null
                        sourceLabel.text = sourceText()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                adapter.loadThumbnails()
            }
        }.start()
    }

    private inner class AlbumAdapter(val albums: List<Albums.Album>) : BaseAdapter() {
        private val thumbs = HashMap<String, Bitmap>()

        fun loadThumbnails() = Thread {
            for (a in albums) {
                val bmp = Albums.thumbnail(contentResolver, a.coverId) ?: continue
                runOnUiThread {
                    thumbs[a.id] = bmp
                    notifyDataSetChanged()
                }
            }
        }.start()

        override fun getCount() = albums.size
        override fun getItem(position: Int) = albums[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFFDDDDDD.toInt())
                }, LinearLayout.LayoutParams(dp(72), dp(72)))
                addView(TextView(context).apply {
                    textSize = 20f
                    setPadding(dp(16), 0, 0, 0)
                })
            }
            val a = albums[position]
            (row.getChildAt(0) as ImageView).setImageBitmap(thumbs[a.id])
            (row.getChildAt(1) as TextView).text =
                "${a.name}\n" + resources.getQuantityString(R.plurals.photo_count, a.count, a.count)
            return row
        }
    }

    private fun sourceText(): String {
        prefs.albumName?.let { if (prefs.albumId != null) return getString(R.string.album, it) }
        val uri = prefs.folderUri ?: return getString(R.string.folder_none)
        return try {
            getString(R.string.folder, DocumentsContract.getTreeDocumentId(Uri.parse(uri)))
        } catch (_: Exception) {
            getString(R.string.folder, uri)
        }
    }

    private fun localIps(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLoopbackAddress }
            .joinToString(", ") { it.hostAddress ?: "" }
            .ifEmpty { getString(R.string.ip_not_connected) }
    } catch (_: Exception) {
        getString(R.string.ip_unknown)
    }

    // ---------------------------------------------------------------- tiny form builder

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun heading(textRes: Int) {
        col.addView(TextView(this).apply {
            setText(textRes)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(28), 0, dp(4))
        })
    }

    private fun label(text: String, size: Float = 16f) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dp(10), 0, 0)
    }.also { col.addView(it) }

    private fun textField(
        title: Int, value: String,
        type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
        save: (String) -> Unit,
    ) {
        label(getString(title))
        val e = EditText(this).apply {
            setText(value)
            inputType = type
            textSize = 20f
            isSingleLine = true
        }
        col.addView(e)
        savers += { save(e.text.toString()) }
    }

    private fun intField(title: Int, value: Int, range: IntRange, save: (Int) -> Unit) {
        label(getString(title))
        val e = EditText(this).apply {
            setText(value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 20f
            isSingleLine = true
        }
        col.addView(e)
        savers += { e.text.toString().trim().toIntOrNull()?.let { save(it.coerceIn(range)) } }
    }

    private fun checkBox(title: Int, value: Boolean, save: (Boolean) -> Unit) {
        val c = CheckBox(this).apply {
            setText(title)
            textSize = 18f
            isChecked = value
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(c)
        savers += { save(c.isChecked) }
    }

    private fun button(title: Int, onClick: () -> Unit) {
        col.addView(Button(this).apply {
            setText(title)
            textSize = 18f
            isAllCaps = false
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_FOLDER = 10
        private const val REQ_READ = 11
    }
}
