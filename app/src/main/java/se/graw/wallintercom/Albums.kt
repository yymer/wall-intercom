package se.graw.wallintercom

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Photo albums as gallery apps show them: every folder on the tablet that contains pictures
 * (Camera, Download, Screenshots, …). Read from Android's media library, which needs the
 * "read photos" permission. Albums that only exist in the cloud (e.g. made in Google Photos)
 * are not on the tablet and don't show up here.
 */
object Albums {
    private const val TAG = "Albums"

    data class Album(val id: String, val name: String, val count: Int, val coverId: Long)

    /** The app's own album, filled by sharing photos to Wall Intercom from other apps. */
    const val OWN_ALBUM = "Wall Intercom"

    sealed class Import {
        class Added(val uri: Uri) : Import()
        class AlreadyThere(val uri: Uri) : Import()
        object Failed : Import()
    }

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

    /** Which album a picture is in: (id, name). */
    fun albumOf(cr: ContentResolver, image: Uri): Pair<String, String>? = try {
        cr.query(
            image,
            arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst() && c.getString(0) != null) c.getString(0) to (c.getString(1) ?: OWN_ALBUM) else null }
    } catch (e: Exception) {
        Log.w(TAG, "can't find album of $image", e)
        null
    }

    /**
     * Copies a shared picture into [OWN_ALBUM] (Pictures/Wall Intercom). A picture with the same
     * name and size that is already there is not copied again. Call off the main thread.
     */
    fun import(ctx: Context, src: Uri): Import {
        val cr = ctx.contentResolver
        if (!looksLikeImage(ctx, src)) {
            Log.w(TAG, "not importing $src: not a picture")
            return Import.Failed
        }
        var name: String? = null
        var size = -1L
        try {
            cr.query(src, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) {
        }
        if (name == null && src.scheme == "file") name = src.lastPathSegment
        val mime = cr.getType(src)?.takeIf { it.startsWith("image/") }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name?.substringAfterLast('.', "")?.lowercase())
            ?: "image/jpeg"
        var fileName = (name ?: "IMG_${System.currentTimeMillis()}").replace('/', '_')
        if (!fileName.contains('.')) {
            fileName += "." + (MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg")
        }
        return try {
            if (Build.VERSION.SDK_INT >= 29) importQ(cr, src, fileName, mime, size) else importLegacy(ctx, src, fileName, size)
        } catch (e: Exception) {
            Log.w(TAG, "import of $src failed", e)
            Import.Failed
        }
    }

    /**
     * Only real pictures, and never one of our own private files: an exported share target must
     * not let another app copy, say, our settings file into the public Pictures folder.
     */
    private fun looksLikeImage(ctx: Context, src: Uri): Boolean {
        if (src.scheme == "file") {
            val path = File(src.path ?: return false).canonicalPath
            if (path.startsWith(File(ctx.applicationInfo.dataDir).canonicalPath)) return false
        } else if (src.scheme != "content") {
            return false
        }
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, o) }
            o.outWidth > 0 && o.outHeight > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun importQ(cr: ContentResolver, src: Uri, fileName: String, mime: String, size: Long): Import {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relPath = "${Environment.DIRECTORY_PICTURES}/$OWN_ALBUM/"
        if (size >= 0) {
            cr.query(
                collection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.SIZE} = ?",
                arrayOf(relPath, fileName, size.toString()), null,
            )?.use { c -> if (c.moveToFirst()) return Import.AlreadyThere(ContentUris.withAppendedId(collection, c.getLong(0))) }
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val dst = cr.insert(collection, values) ?: return Import.Failed
        try {
            cr.openInputStream(src).use { input ->
                cr.openOutputStream(dst).use { out -> input!!.copyTo(out!!) }
            }
            cr.update(dst, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(dst, null, null)
            throw e
        }
        return Import.Added(dst)
    }

    @Suppress("DEPRECATION")
    private fun importLegacy(ctx: Context, src: Uri, fileName: String, size: Long): Import {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), OWN_ALBUM)
        dir.mkdirs()
        var file = File(dir, fileName)
        if (file.exists() && size >= 0 && file.length() == size) {
            return scan(ctx, file)?.let { Import.AlreadyThere(it) } ?: Import.Failed
        }
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.')
        var n = 1
        while (file.exists()) file = File(dir, "$base (${n++}).$ext")
        try {
            ctx.contentResolver.openInputStream(src).use { input -> file.outputStream().use { input!!.copyTo(it) } }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return scan(ctx, file)?.let { Import.Added(it) } ?: Import.Failed
    }

    /** Adds a file to the media library (so it shows up as part of the album) and returns its uri. */
    private fun scan(ctx: Context, file: File): Uri? {
        var result: Uri? = null
        val done = CountDownLatch(1)
        MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null) { _, uri ->
            result = uri
            done.countDown()
        }
        done.await(15, TimeUnit.SECONDS)
        return result
    }
}
