package se.graw.wallintercom

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.Executors

/** Shows which pictures the slideshow is using, as a grid of thumbnails. */
class PicturesActivity : Activity() {
    private val loader = Executors.newFixedThreadPool(2)
    private val thumbs = object : LruCache<Uri, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.byteCount
    }
    private lateinit var grid: GridView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(150)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(4)
            verticalSpacing = dp(4)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToPadding = false
        }
        empty = TextView(this).apply {
            setText(R.string.no_pictures_yet)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply {
            addView(grid)
            addView(empty, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ))
        })

        val perm = Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), REQ_READ)
        } else {
            load()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode == REQ_READ) load()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != android.R.id.home) return super.onOptionsItemSelected(item)
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
    }

    private fun load() = loader.execute {
        val images = Albums.slideshowImages(contentResolver, Prefs(this))
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            grid.adapter = Adapter(images)
            empty.visibility = if (images.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private inner class Adapter(val images: List<Uri>) : BaseAdapter() {
        override fun getCount() = images.size
        override fun getItem(position: Int) = images[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView as? ImageView ?: ImageView(this@PicturesActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFDDDDDD.toInt())
                layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, dp(150))
            }
            val uri = images[position]
            view.tag = uri
            val cached = thumbs.get(uri)
            view.setImageBitmap(cached)
            if (cached == null) loader.execute {
                // Scrolled past already: this view shows another picture now.
                if (view.tag != uri) return@execute
                val bmp = Albums.thumbnail(contentResolver, uri) ?: return@execute
                thumbs.put(uri, bmp)
                runOnUiThread { if (view.tag == uri) view.setImageBitmap(bmp) }
            }
            return view
        }
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val REQ_READ = 1
    }
}
