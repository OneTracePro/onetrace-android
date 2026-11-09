package pro.onetrace.sdk.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Images of in-app messages and feed cards: downloaded on a background thread without a third-party library, scaled
 * down to the size they are shown at and kept in memory, so a feed redrawn after a refresh does not download them again.
 */
internal object ImageLoader {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "onetrace-images").apply { isDaemon = true } }
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Shows the image of [url] in [view] once it is downloaded; a view reused for another image before that keeps the
     * other one. [maxWidth] is the widest the image is shown, in pixels.
     */
    fun load(view: ImageView, url: String, maxWidth: Int) {
        view.tag = url
        cache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }

        view.setImageDrawable(null)
        executor.execute {
            val bitmap = download(url, maxWidth) ?: return@execute
            cache.put(url, bitmap)
            main.post {
                if (view.tag == url) {
                    view.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun download(url: String, maxWidth: Int): Bitmap? = try {
        val bytes = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 10_000
            try {
                inputStream.use { it.readBytes() }
            } finally {
                disconnect()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1

        while (maxWidth > 0 && bounds.outWidth / (sample * 2) >= maxWidth) {
            sample *= 2
        }

        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
