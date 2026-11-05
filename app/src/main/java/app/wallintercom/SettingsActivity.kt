package app.wallintercom

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
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
    private lateinit var folderLabel: TextView
    private val savers = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
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

        heading(R.string.h_other_tablet)
        textField(R.string.peer_ip, prefs.peerIp, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) {
            prefs.peerIp = it.trim()
        }
        intField(R.string.port, prefs.port, 1024..65535) { prefs.port = it }

        heading(R.string.h_slideshow)
        folderLabel = label(folderText(), 18f)
        button(R.string.choose_folder) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
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
        button(R.string.allow_overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button(R.string.allow_battery) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast(getString(R.string.already_allowed))
            } else {
                @Suppress("BatteryLife")
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
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
            folderLabel.text = folderText()
        }
    }

    private fun folderText(): String {
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
    }
}
