package com.redtermapp.util

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object Format {
    fun size(sizeBytes: Long): String = when {
        sizeBytes < 1_000_000 -> "${sizeBytes / 1000} KB"
        sizeBytes < 1_000_000_000 -> "${"%.1f".format(sizeBytes / 1_000_000.0)} MB"
        else -> "${"%.2f".format(sizeBytes / 1_000_000_000.0)} GB"
    }

    fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private data class CacheEntry(val bytes: Long, val stamp: Long)

    private const val TTL_MS = 60_000L
    private val sizeCache = ConcurrentHashMap<String, CacheEntry>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "redterm-dirsize").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun cachedSize(dir: File): Long? =
        sizeCache[dir.absolutePath]
            ?.takeIf { System.currentTimeMillis() - it.stamp < TTL_MS }
            ?.bytes

    fun invalidate(dir: File) {
        sizeCache.remove(dir.absolutePath)
    }

    fun invalidateAll() {
        sizeCache.clear()
    }

    fun dirSizeAsync(dir: File, onResult: (Long) -> Unit) {
        cachedSize(dir)?.let { onResult(it); return }
        executor.execute {
            val bytes = try {
                dirSize(dir)
            } catch (_: Exception) {
                0L
            }
            sizeCache[dir.absolutePath] = CacheEntry(bytes, System.currentTimeMillis())
            mainHandler.post { onResult(bytes) }
        }
    }
}
