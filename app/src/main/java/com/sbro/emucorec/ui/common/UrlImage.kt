package com.sbro.emucorec.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest

/**
 * Default longest edge a downloaded image is decoded at. Call sites that know
 * their layout size pass a tighter bound: decoding art much larger than the
 * tile renders evicts most of the memory cache, and every mode switch or scroll
 * then re-decodes from disk -- visible as tiles that are empty and then pop in.
 * Decoding at the size the tile actually renders keeps the cache holding whole
 * screens and looks identical.
 */
private const val DEFAULT_DECODED_EDGE_PX = 1024

@Composable
fun UrlImage(
    imageUrl: String?,
    contentDescription: String,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
    fallbackPath: String? = null,
    pinInMemory: Boolean = false,
    decodeEdgePx: Int = DEFAULT_DECODED_EDGE_PX,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val decodeEdge = decodeEdgePx.coerceAtLeast(64)
    // One canonical URL for the memory key, the disk key and the request, so
    // the same cover rendered as a grid tile, a list thumbnail or a hero never
    // misses the cache and never re-downloads. Keys are URL-only (not size):
    // whatever decode is already available is shown immediately and upgraded
    // in place below if the current layout needs a bigger one.
    val canonicalUrl = imageUrl?.takeIf { it.isNotBlank() }?.let(::igdbCoverUrl)
    val initial = canonicalUrl?.let { UrlBitmapMemoryCache.get(it) }
    val bitmapState = produceState<Bitmap?>(
        initialValue = initial,
        canonicalUrl,
        fallbackPath,
        context,
        decodeEdge
    ) {
        val loadedBitmap = loadUrlImageBitmap(
            context, canonicalUrl, fallbackPath, decodeEdge, pinInMemory
        )
        value = loadedBitmap
    }
    val bitmap = bitmapState.value

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
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


private val urlImageLoads = Semaphore(4)
// Bounded locks coalesce a prefetched URL with a card that reaches the viewport.
private val urlImageLocks = Array(32) { Mutex() }

internal suspend fun loadUrlImageBitmap(
    context: Context,
    imageUrl: String?,
    fallbackPath: String?,
    decodeEdgePx: Int,
    pinInMemory: Boolean = false
): Bitmap? {
    val canonicalUrl = imageUrl?.takeIf { it.isNotBlank() }?.let(::igdbCoverUrl)
    val decodeEdge = decodeEdgePx.coerceAtLeast(64)
    val key = canonicalUrl ?: fallbackPath ?: return null
    return urlImageLocks[(key.hashCode() and Int.MAX_VALUE) % urlImageLocks.size].withLock {
        urlImageLoads.withPermit {
            withContext(Dispatchers.IO) {
            if (canonicalUrl != null) {
                val cachedInMemory = UrlBitmapMemoryCache.get(canonicalUrl)
                if (cachedInMemory != null && isLargeEnough(cachedInMemory, decodeEdge)) {
                    // Already displayed via initialValue; nothing to do.
                    return@withContext cachedInMemory
                }

                val cachedFile = UrlImageDiskCache.getCachedFile(context, canonicalUrl)
                if (cachedFile != null) {
                    val diskBitmap = decodeBounded(cachedFile.absolutePath, decodeEdge)
                    if (diskBitmap != null) {
                        UrlBitmapMemoryCache.put(canonicalUrl, diskBitmap, pinned = pinInMemory)
                        return@withContext diskBitmap
                    }
                }

                val fetched = runCatching {
                    if (canonicalUrl.startsWith("content://") || canonicalUrl.startsWith("file://")) {
                        decodeBounded(decodeEdge) {
                            context.contentResolver.openInputStream(Uri.parse(canonicalUrl))
                        }
                    } else {
                        val downloaded = UrlImageDiskCache.downloadToCache(context, canonicalUrl)
                        if (downloaded != null) {
                            decodeBounded(downloaded.absolutePath, decodeEdge)
                        } else {
                            // A failed cache/download must not fall back to an
                            // unbounded full-resolution stream allocation.
                            null
                        }
                    }
                }.getOrNull()

                if (fetched != null) {
                    UrlBitmapMemoryCache.put(canonicalUrl, fetched, pinned = pinInMemory)
                    return@withContext fetched
                }

                // The remote cover could not be loaded: keep showing the
                // smaller decode we already have instead of downgrading to the
                // local icon.
                if (cachedInMemory != null) {
                    return@withContext cachedInMemory
                }
            }

            // Local file fallback if the remote cover could not be loaded
            if (!fallbackPath.isNullOrBlank()) {
                val fallbackKey = "local:$fallbackPath"
                val cachedFallback = UrlBitmapMemoryCache.get(fallbackKey)
                if (cachedFallback != null && isLargeEnough(cachedFallback, decodeEdge)) cachedFallback
                else decodeBounded(fallbackPath, decodeEdge)?.also {
                    UrlBitmapMemoryCache.put(fallbackKey, it)
                }
            } else {
                null
            }
            }
        }
    }
}

private fun decodeBounded(path: String, maxEdgePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxEdgePx ||
        bounds.outHeight / (sample * 2) >= maxEdgePx
    ) {
        sample *= 2
    }

    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun decodeBounded(maxEdgePx: Int, openStream: () -> InputStream?): Bitmap? =
    runCatching {
        // Providers need not support seek/reset: open fresh streams for bounds
        // and pixels, just as the file-backed decoder does in its two passes.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxEdgePx ||
            bounds.outHeight / (sample * 2) >= maxEdgePx
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

/** True when the decoded bitmap is at least as large as this render needs. */
private fun isLargeEnough(bitmap: Bitmap, decodeEdgePx: Int): Boolean =
    maxOf(bitmap.width, bitmap.height) >= decodeEdgePx

object UrlImageDiskCache {
    private const val CACHE_DIR_NAME = "image_cache"

    fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCachedFile(context: Context, url: String): File? {
        return runCatching {
            val key = hashKey(url)
            val file = File(getCacheDir(context), key)
            if (file.exists() && file.length() > 0) file else null
        }.getOrNull()
    }

    fun downloadToCache(context: Context, url: String): File? {
        return runCatching {
            val cacheDir = getCacheDir(context)
            val key = hashKey(url)
            val tempFile = File(cacheDir, "$key.tmp")
            val targetFile = File(cacheDir, key)

            val conn = URL(url).openConnection()
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.getInputStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                targetFile
            } else {
                tempFile.delete()
                null
            }
        }.getOrNull()
    }

    fun getCacheSizeBytes(context: Context): Long {
        return runCatching {
            val dir = getCacheDir(context)
            if (!dir.exists() || !dir.isDirectory) return@runCatching 0L
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    fun clearCache(context: Context): Long {
        return runCatching {
            val dir = getCacheDir(context)
            if (!dir.exists() || !dir.isDirectory) return@runCatching 0L
            var bytesFreed = 0L
            dir.listFiles()?.forEach { file ->
                val size = if (file.isFile) file.length() else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (file.deleteRecursively()) {
                    bytesFreed += size
                }
            }
            UrlBitmapMemoryCache.clear()
            bytesFreed
        }.getOrDefault(0L)
    }

    private fun hashKey(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

object UrlBitmapMemoryCache {
    private val pinnedCache = mutableMapOf<String, Bitmap>()
    private val cache = object : LruCache<String, Bitmap>(calculateCacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    @Synchronized
    fun get(url: String): Bitmap? = pinnedCache[url] ?: cache.get(url)

    @Synchronized
    fun put(url: String, bitmap: Bitmap, pinned: Boolean = false) {
        if (pinned) {
            pinnedCache[url] = bitmap
            return
        }
        // Keep the largest decode seen for this URL; a later, larger decode
        // (thumb upgraded to tile or hero) replaces a smaller entry.
        val existing = pinnedCache[url] ?: cache.get(url)
        if (existing != null && maxOf(existing.width, existing.height) >= maxOf(bitmap.width, bitmap.height)) {
            return
        }
        cache.put(url, bitmap)
    }

    @Synchronized
    fun clear() {
        pinnedCache.clear()
        cache.evictAll()
    }

    private fun calculateCacheSizeKb(): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        return (maxMemoryKb / 8).coerceAtLeast(8 * 1024)
    }
}
