package pro.onetrace.sdk.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Images of push notifications: downloaded in parallel, up to 10 seconds and 1 MB each, and kept in the cache
 * directory for paging a carousel and for a snoozed notification without network.
 */
internal object PushImages {
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_WIDTH = 720
    private const val KEEP_MS = 3 * 24 * 3600_000L

    /** The images of [urls] in their order, null for those that failed; [cacheOnly] downloads nothing. */
    fun load(context: Context, key: String, urls: List<String?>, cacheOnly: Boolean = false): List<Bitmap?> {
        val directory = File(context.cacheDir, "onetrace-push").apply { mkdirs() }
        cleanUp(directory)
        val files = urls.indices.map { File(directory, "$key-$it") }
        val missing = urls.indices.filter { index -> !files[index].exists() && Links.image(urls[index]) != null }

        if (!cacheOnly && missing.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(missing.size.coerceAtMost(5)) { runnable -> Thread(runnable, "onetrace-push-images").apply { isDaemon = true } }

            try {
                val tasks = missing.map { index -> Callable { download(urls[index]!!)?.let { bytes -> files[index].writeBytes(bytes) } } }
                pool.invokeAll(tasks, TIMEOUT_MS + 2_000L, TimeUnit.MILLISECONDS)
            } finally {
                pool.shutdownNow()
            }
        }

        return files.map { file -> if (file.exists()) decode(file) else null }
    }

    private fun download(url: String): ByteArray? {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return null
        }

        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val deadline = System.currentTimeMillis() + TIMEOUT_MS

            if (connection.responseCode != 200 || connection.contentLengthLong > MAX_BYTES) {
                return null
            }

            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                val output = java.io.ByteArrayOutputStream()

                while (true) {
                    val read = input.read(buffer)

                    if (read < 0) {
                        break
                    }

                    output.write(buffer, 0, read)

                    if (output.size() > MAX_BYTES || System.currentTimeMillis() > deadline) {
                        return null
                    }
                }

                output.toByteArray()
            }
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1

        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) {
            sample *= 2
        }

        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun cleanUp(directory: File) {
        val old = System.currentTimeMillis() - KEEP_MS
        directory.listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
    }
}
