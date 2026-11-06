package se.graw.wallintercom

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast

/** Opens the tablet's own photos app. There's no standard way to open a certain album in it. */
object PhotosApp {
    /** What opens the photos app on this tablet, or null if it has none. */
    fun intent(act: Activity): Intent? {
        val gallery = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
        // Not every photos app says it's a gallery, but most can show the tablet's pictures.
        val anyViewer = Intent(Intent.ACTION_VIEW).setType("image/*")
            .setData(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        return listOf(gallery, anyViewer).firstOrNull { it.resolveActivity(act.packageManager) != null }
    }

    fun open(act: Activity, intent: Intent) = start(act, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Shows one picture full size in the tablet's default viewer. */
    fun viewPicture(act: Activity, picture: Uri) {
        start(
            act,
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(picture, act.contentResolver.getType(picture) ?: "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun start(act: Activity, intent: Intent) {
        // In the locked-down kiosk mode (see README), other apps can't open until it's unpinned.
        // The main screen pins itself again when it comes back.
        try {
            act.stopLockTask()
        } catch (_: Exception) {
        }
        try {
            act.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(act, R.string.no_photos_app, Toast.LENGTH_LONG).show()
        }
    }
}
