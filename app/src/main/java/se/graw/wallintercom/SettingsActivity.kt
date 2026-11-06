package se.graw.wallintercom

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Calendar

/**
 * The settings: one main page, plus pages for what most people never need. Everything is saved as
 * it's changed; text fields when you leave them, or the screen.
 */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var scroll: ScrollView
    private lateinit var mainPage: LinearLayout
    private lateinit var albumPage: LinearLayout
    private lateinit var advancedPage: LinearLayout
    private var page: LinearLayout? = null
    /** Where the form builder functions below add their rows. */
    private lateinit var col: LinearLayout

    private lateinit var picturesSummary: TextView
    private lateinit var sourceSummary: TextView
    private lateinit var resetAlbum: View
    private lateinit var devicesCol: LinearLayout
    private var shownDevices: List<CallManager.Device>? = null

    /** Save what's typed in each text field. */
    private val fields = mutableListOf<() -> Unit>()
    /** What the listener and the other tablets know about: these only change when leaving settings. */
    private var appliedPort = 0
    private var appliedName = ""
    private val handler = Handler(Looper.getMainLooper())
    private val refreshDevices = object : Runnable {
        override fun run() {
            rebuildDevices()
            handler.postDelayed(this, DEVICES_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        appliedPort = prefs.port
        appliedName = prefs.myName
        CallManager.init(this)

        mainPage = newPage()
        albumPage = newPage()
        advancedPage = newPage()
        col = mainPage
        buildMainPage()
        col = albumPage
        buildAlbumPage()
        col = advancedPage
        buildAdvancedPage()

        scroll = ScrollView(this)
        setContentView(scroll)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        show(mainPage)
    }

    private fun buildMainPage() {
        section(R.string.h_slideshow) {
            shareTipRow()
            navRow(R.string.change_album, getString(R.string.change_album_hint)) { show(albumPage) }
            picturesSummary = navRow(R.string.see_pictures, "") {
                startActivity(Intent(this, PicturesActivity::class.java))
            }
            intRow(R.string.slide_seconds, null, prefs.slideSeconds, 5..86400) { prefs.slideSeconds = it }
            switchRow(R.string.fill_screen, R.string.fill_screen_hint, prefs.fillScreen) { prefs.fillScreen = it }
        }
        section(R.string.h_this_device) {
            textRow(R.string.my_name, prefs.myName) { typed ->
                typed.trim().ifEmpty { getString(R.string.default_my_name) }.also { prefs.myName = it }
            }
            switchRow(R.string.auto_start, R.string.auto_start_hint, prefs.autoStart) { prefs.autoStart = it }
        }
        section(R.string.h_paired_devices) {
            devicesCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(devicesCol)
        }
        section(R.string.h_screen) {
            intRow(R.string.idle_brightness, null, prefs.idleBrightness, 0..100) { prefs.idleBrightness = it }
            val nightRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.withDividers()
            nightRows.visibility = if (prefs.nightMode) View.VISIBLE else View.GONE
            switchRow(R.string.night_mode, R.string.night_mode_hint, prefs.nightMode) { on ->
                prefs.nightMode = on
                nightRows.visibility = if (on) View.VISIBLE else View.GONE
            }
            col.addView(nightRows)
            val card = col
            col = nightRows
            timeRow(R.string.night_start, prefs.nightStart) { prefs.nightStart = it }
            timeRow(R.string.night_end, prefs.nightEnd) { prefs.nightEnd = it }
            intRow(R.string.night_brightness, R.string.night_brightness_hint, prefs.nightBrightness, 0..100) {
                prefs.nightBrightness = it
            }
            col = card
        }
        section(R.string.h_calls) {
            switchRow(R.string.auto_answer, R.string.auto_answer_hint, prefs.autoAnswer) { prefs.autoAnswer = it }
            intRow(R.string.call_volume, null, prefs.callVolume, 0..100) { prefs.callVolume = it }
            intRow(R.string.bell_volume, null, prefs.bellVolume, 0..100) { prefs.bellVolume = it }
        }
        section(R.string.h_permissions) {
            // Android 5 has neither of these restrictions, so nothing to allow there.
            if (Build.VERSION.SDK_INT >= 23) {
                navRow(R.string.allow_overlay) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                navRow(R.string.allow_battery) { allowBattery() }
            }
            navRow(R.string.open_android_settings) {
                try {
                    stopLockTask()
                } catch (_: Exception) {
                }
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        section(null) {
            navRow(R.string.h_advanced, getString(R.string.advanced_hint)) { show(advancedPage) }
        }
    }

    /** For showing something else than the app's own album. */
    private fun buildAlbumPage() {
        section(null) {
            val r = row()
            val texts = titleAndHint(getString(R.string.showing_now), "")
            r.addView(texts)
            col.addView(r)
            sourceSummary = texts.getChildAt(1) as TextView
        }
        section(null) {
            navRow(R.string.choose_album, arrow = false) { chooseAlbum() }
            navRow(R.string.choose_folder, arrow = false) {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        // Show internal storage right away (Android 5 hides it behind a menu).
                        .putExtra("android.content.extra.SHOW_ADVANCED", true),
                    REQ_FOLDER,
                )
            }
        }
        // Only there when something else than the default is chosen.
        resetAlbum = section(null) {
            navRow(R.string.reset_album, getString(R.string.reset_album_hint), arrow = false) {
                prefs.albumId = null
                prefs.albumName = null
                prefs.folderUri = null
                sourceChanged()
            }
        }
    }

    private fun buildAdvancedPage() {
        section(R.string.h_this_device) {
            switchRow(R.string.use_as_home, R.string.use_as_home_hint, Prefs.HomeScreen.isEnabled(this)) {
                Prefs.HomeScreen.setEnabled(this, it)
            }
        }
        section(R.string.h_calls) {
            intRow(R.string.max_minutes, R.string.max_minutes_hint, prefs.maxCallMinutes, 0..1440) {
                prefs.maxCallMinutes = it
            }
            switchRow(R.string.hd_video, R.string.hd_video_hint, prefs.hdVideo) { prefs.hdVideo = it }
            switchRow(R.string.hw_echo, R.string.hw_echo_hint, prefs.hwEcho) { prefs.hwEcho = it }
        }
        section(R.string.h_network) {
            infoRow(getString(R.string.my_ip, localIps()))
            intRow(R.string.port, R.string.port_hint, prefs.port, 1024..65535) { prefs.port = it }
            navRow(R.string.add_by_ip, getString(R.string.add_by_ip_hint)) { addByIp() }
        }
    }

    // ---------------------------------------------------------------- pages

    private fun newPage() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // So that no text field takes the focus, and opens the keyboard, by itself.
        isFocusableInTouchMode = true
    }

    private fun show(target: LinearLayout) {
        page = target
        scroll.removeAllViews()
        scroll.addView(target)
        scroll.scrollTo(0, 0)
        target.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(scroll.windowToken, 0)
        fitToScreen()
        setTitle(
            when (target) {
                albumPage -> R.string.change_album
                advancedPage -> R.string.h_advanced
                else -> R.string.settings_title
            }
        )
    }

    /** On a big tablet, keep the form at a readable width instead of stretching it. */
    private fun fitToScreen() {
        val side = maxOf(dp(16), (dp(resources.configuration.screenWidthDp) - dp(MAX_WIDTH_DP)) / 2)
        page?.setPadding(side, dp(8), side, dp(24))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fitToScreen()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page !== mainPage) show(mainPage) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != android.R.id.home) return super.onOptionsItemSelected(item)
        if (page !== mainPage) show(mainPage) else finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        CallManager.pingNow()
        handler.post(refreshDevices)
        refreshSource()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshDevices)
        applyChanges()
    }

    /** Saves the text fields, and tells the network if the port or the name changed. */
    private fun applyChanges() {
        fields.forEach { it() }
        val port = prefs.port
        val name = prefs.myName
        if (port == appliedPort && name == appliedName) return
        if (port != appliedPort) CallManager.restartListener()
        CallManager.pingNow()
        appliedPort = port
        appliedName = name
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
            sourceChanged()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode != REQ_READ) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) showAlbums()
        else toast(getString(R.string.need_photo_permission))
    }

    private fun allowBattery() {
        if (Build.VERSION.SDK_INT < 23) return
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

    // ---------------------------------------------------------------- devices

    /** Paired tablets and new ones on the network. Only redrawn when something changed. */
    private fun rebuildDevices(force: Boolean = false) {
        val devices = CallManager.allDevices()
        if (!force && devices == shownDevices) return
        shownDevices = devices
        devicesCol.removeAllViews()
        if (devices.isEmpty()) {
            devicesCol.addView(rowText(getString(R.string.devices_none), 16f, TEXT_SECONDARY).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
            })
            return
        }
        devices.forEachIndexed { i, d ->
            if (i > 0) devicesCol.addView(divider())
            devicesCol.addView(deviceRow(d))
        }
    }

    private fun deviceRow(d: CallManager.Device) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(8), dp(8))
        minimumHeight = dp(64)
        addView(titleAndHint(
            d.name,
            getString(
                when {
                    !d.paired -> R.string.device_not_paired
                    d.online -> R.string.device_online
                    else -> R.string.device_offline
                }
            ),
        ), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            setText(if (d.paired) R.string.remove else R.string.pair)
            textSize = 16f
            isAllCaps = false
            if (!d.paired) setTextColor(GREEN)
            setOnClickListener { if (d.paired) confirmRemove(d) else pair(d.ip, d.port, d.name) }
        })
    }

    private fun confirmRemove(d: CallManager.Device) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_confirm, d.name))
            .setPositiveButton(R.string.remove) { _, _ ->
                CallManager.unpair(d.id)
                rebuildDevices(force = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pair(ip: String, port: Int, name: String?) {
        applyChanges()
        PairingDialogs.pair(this, ip, port, name, onPaired = { rebuildDevices(force = true) })
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
                    applyChanges()
                    pair(ip, prefs.port, null)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- albums

    /** A new album or folder was chosen. */
    private fun sourceChanged() {
        refreshSource()
        show(mainPage)
    }

    /** Shows what the slideshow uses, and how many pictures that is. */
    private fun refreshSource() {
        val name = sourceText()
        picturesSummary.text = name
        sourceSummary.text = name
        // Sharing a photo stores the app's own album as the chosen one, which is still the default.
        val isDefault = prefs.folderUri == null && (prefs.albumId == null || prefs.albumName == Albums.OWN_ALBUM)
        resetAlbum.visibility = if (isDefault) View.GONE else View.VISIBLE
        Thread {
            val count = Albums.slideshowImages(contentResolver, prefs).size
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val text = name + " · " + resources.getQuantityString(R.plurals.photo_count, count, count)
                picturesSummary.text = text
                sourceSummary.text = text
            }
        }.start()
    }

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
                        sourceChanged()
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
                gravity = Gravity.CENTER_VERTICAL
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
        if (prefs.albumId != null) return getString(R.string.album, prefs.albumName ?: "?")
        val uri = prefs.folderUri ?: return getString(R.string.album, Albums.OWN_ALBUM)
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

    /** A heading and a card with the rows that [build] adds. */
    private fun section(title: Int?, build: () -> Unit): View {
        val outer = col
        if (title != null) {
            outer.addView(TextView(this).apply {
                setText(title)
                textSize = 15f
                setTextColor(GREEN)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(16), dp(20), dp(16), dp(8))
            })
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(1).toFloat()
            clipToOutline = true
        }.withDividers()
        col = card
        build()
        col = outer
        outer.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { if (title == null) topMargin = dp(20) })
        return card
    }

    private fun LinearLayout.withDividers() = apply {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply {
            setColor(DIVIDER)
            setSize(1, 1)
        }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun rowText(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun titleAndHint(title: String, hint: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(rowText(title, 18f, TEXT_PRIMARY))
        if (hint != null) addView(rowText(hint, 14f, TEXT_SECONDARY))
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minimumHeight = dp(64)
    }

    private fun LinearLayout.addTitle(title: Int, hint: Int?) = addView(
        titleAndHint(getString(title), hint?.let { getString(it) }),
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )

    private fun View.clickable(onClick: () -> Unit) {
        val ripple = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
        setOnClickListener { onClick() }
    }

    /** How to add photos, with a link to the app they're in. */
    private fun shareTipRow() {
        val photos = PhotosApp.intent(this) ?: return infoRow(getString(R.string.share_tip))
        val r = row()
        r.addView(
            rowText(getString(R.string.share_tip), 16f, TEXT_PRIMARY),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        r.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.open_photos)
            textSize = 16f
            isAllCaps = false
            setTextColor(GREEN)
            setOnClickListener { PhotosApp.open(this@SettingsActivity, photos) }
        })
        col.addView(r)
    }

    private fun infoRow(text: String) {
        col.addView(rowText(text, 16f, TEXT_PRIMARY).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) })
    }

    /** Saves when you leave the field or the screen, not on every key: 15 is 1 on the way there. */
    private fun EditText.saveOnLeave(save: () -> Unit) {
        setOnFocusChangeListener { _, focused -> if (!focused) save() }
        fields += save
    }

    /** [save] returns what was stored, which the field then shows. */
    private fun textRow(title: Int, value: String, save: (String) -> String) {
        val r = row()
        r.addTitle(title, null)
        val e = EditText(this).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            textSize = 18f
            gravity = Gravity.END
            isSingleLine = true
        }
        r.addView(e, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))
        col.addView(r)
        e.saveOnLeave {
            val stored = save(e.text.toString())
            if (e.text.toString() != stored) e.setText(stored)
        }
    }

    private fun intRow(title: Int, hint: Int?, value: Int, range: IntRange, save: (Int) -> Unit) {
        val r = row()
        r.addTitle(title, hint)
        val e = EditText(this).apply {
            setText(value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 18f
            gravity = Gravity.END
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        r.addView(e, LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(r)
        var stored = value
        e.saveOnLeave {
            val typed = e.text.toString().trim().toIntOrNull()?.coerceIn(range) ?: stored
            if (typed != stored) {
                save(typed)
                stored = typed
            }
            if (e.text.toString() != typed.toString()) e.setText(typed.toString())
        }
    }

    private fun switchRow(title: Int, hint: Int?, value: Boolean, save: (Boolean) -> Unit) {
        val r = row()
        r.addTitle(title, hint)
        val s = Switch(this).apply {
            isChecked = value
            setPadding(dp(16), 0, 0, 0)
        }
        r.addView(s)
        r.clickable { s.toggle() }
        col.addView(r)
        s.setOnCheckedChangeListener { _, on -> save(on) }
    }

    /** A time of day, in minutes after midnight, chosen with Android's own clock. */
    private fun timeRow(title: Int, value: Int, save: (Int) -> Unit) {
        var minutes = value
        val r = row()
        r.addTitle(title, null)
        val shown = rowText(timeText(minutes), 18f, TEXT_PRIMARY)
        r.addView(shown)
        r.clickable {
            TimePickerDialog(this, { _, hour, minute ->
                minutes = hour * 60 + minute
                save(minutes)
                shown.text = timeText(minutes)
            }, minutes / 60, minutes % 60, DateFormat.is24HourFormat(this)).show()
        }
        col.addView(r)
    }

    private fun timeText(minutes: Int): String {
        val time = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
        }
        return DateFormat.getTimeFormat(this).format(time.time)
    }

    /**
     * A row that does something when tapped, with an arrow if that's going to another page.
     * Returns its hint line, to change it later.
     */
    private fun navRow(title: Int, hint: String? = null, arrow: Boolean = true, onClick: () -> Unit): TextView {
        val r = row()
        val texts = titleAndHint(getString(title), hint)
        r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (arrow) r.addView(rowText("›", 26f, TEXT_SECONDARY).apply { setPadding(dp(16), 0, 0, 0) })
        r.clickable(onClick)
        col.addView(r)
        return texts.getChildAt(texts.childCount - 1) as TextView
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_FOLDER = 10
        private const val REQ_READ = 11
        private const val DEVICES_REFRESH_MS = 3_000L
        private const val MAX_WIDTH_DP = 760
        private const val GREEN = 0xFF25803F.toInt()
        private const val DIVIDER = 0xFFE3E6E4.toInt()
        private const val TEXT_PRIMARY = 0xFF1C1F1D.toInt()
        private const val TEXT_SECONDARY = 0xFF5F6662.toInt()
    }
}
