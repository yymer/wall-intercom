package app.wallintercom

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size

/**
 * Photo albums as gallery apps show them: every folder on the tablet that contains pictures
 * (Camera, Download, Screenshots, …). Read from Android's media library, which needs the
 * "read photos" permission. Albums that only exist in the cloud (e.g. made in Google Photos)
 * are not on the tablet and don't show up here.
 */
object Albums {
    private const val TAG = "Albums"

    data class Album(val id: String, val name: String, val count: Int, val coverId: Long)

    private val imagesUri: Uri
        get() = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) // includes SD cards
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    /** All albums with at least one picture, largest first. Slow-ish: call off the main thread. */
    fun list(cr: ContentResolver): List<Album> {
        class Acc(val name: String, var count: Int, val coverId: Long)
        val byId = LinkedHashMap<String, Acc>()
        try {
            cr.query(
                imagesUri,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.BUCKET_ID,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                ),
                null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC", // newest first -> newest is the cover
            )?.use { c ->
                while (c.moveToNext()) {
                    val bucket = c.getString(1) ?: continue
                    val acc = byId[bucket]
                    if (acc == null) byId[bucket] = Acc(c.getString(2) ?: "?", 1, c.getLong(0))
                    else acc.count++
                }
            }
        } catch (e: Exception) { // SecurityException without the permission
            Log.w(TAG, "can't list albums", e)
        }
        return byId.map { (id, a) -> Album(id, a.name, a.count, a.coverId) }.sortedByDescending { it.count }
    }

    /** Pictures in one album, or an empty list if it's gone or we lack the permission. */
    fun images(cr: ContentResolver, albumId: String): List<Uri> {
        val out = ArrayList<Uri>()
        try {
            cr.query(
                imagesUri,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(albumId),
                null,
            )?.use { c ->
                while (c.moveToNext()) out += ContentUris.withAppendedId(imagesUri, c.getLong(0))
            }
        } catch (e: Exception) {
            Log.w(TAG, "can't list album $albumId", e)
        }
        return out
    }

    /** A small preview of one picture, for the album list. Call off the main thread. */
    fun thumbnail(cr: ContentResolver, imageId: Long): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            cr.loadThumbnail(ContentUris.withAppendedId(imagesUri, imageId), Size(256, 256), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(cr, imageId, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    } catch (e: Exception) {
        Log.w(TAG, "no thumbnail for $imageId", e)
        null
    }
}
