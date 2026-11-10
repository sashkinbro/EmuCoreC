package com.sbro.emucorec.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@Composable
fun LocalImage(
    path: String?,
    contentDescription: String,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    // Decoding used to run inline in a remember() on the composition thread at
    // full resolution: a library grid with many local icons decoded every tile
    // on the main thread and janked scrolling. Decode once, off the main
    // thread, downsampled to what a tile can actually show, and keep the result
    // in a small LRU so scrolling back does not decode again.
    val bitmap by key(path) {
        produceState<Bitmap?>(initialValue = LocalBitmapCache.get(path)) {
            if (value == null) {
                value = localImageLoads.withPermit {
                    withContext(Dispatchers.IO) { LocalBitmapCache.load(path) }
                }
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = fallbackLabel.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val localImageLoads = Semaphore(4)

/** Share the displayed icon cache and its 512-pixel decode bound. */
internal suspend fun preloadLocalImage(path: String) {
    if (LocalBitmapCache.get(path) != null) return
    localImageLoads.withPermit {
        withContext(Dispatchers.IO) { LocalBitmapCache.load(path) }
    }
}

private object LocalBitmapCache {
    /** Longest edge a decoded tile icon is kept at; larger originals add nothing. */
    private const val MAX_DECODED_EDGE_PX = 512

    private val cache = object : LruCache<String, Bitmap>(calculateCacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val images = DecodingImageCache(
        read = cache::get,
        write = { path, bitmap -> cache.put(path, bitmap) },
        evict = cache::evictAll,
        decode = ::decodeDownsampled
    )

    fun get(path: String?): Bitmap? = images.get(path)

    fun load(path: String?): Bitmap? = images.load(path)

    fun clear() = images.clear()

    private fun decodeDownsampled(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DECODED_EDGE_PX ||
            bounds.outHeight / (sample * 2) >= MAX_DECODED_EDGE_PX
        ) {
            sample *= 2
        }

        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun calculateCacheSizeKb(): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        return (maxMemoryKb / 8).coerceAtLeast(8 * 1024)
    }
}
