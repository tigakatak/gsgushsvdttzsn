package com.redt.util

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

    fun dirSize(dir: File): Long {
        var total = 0L
        val visited = mutableSetOf<String>()
        fun walk(d: File) {
            val canonical = try { d.canonicalPath } catch (_: Exception) { d.absolutePath }
            if (canonical in visited) return
            visited.add(canonical)
            d.listFiles()?.forEach { f ->
                val isSymlink = try {
                    java.nio.file.Files.isSymbolicLink(f.toPath())
                } catch (_: Exception) { false }
                if (!isSymlink) {
                    if (f.isFile) {
                        total += f.length()
                    } else if (f.isDirectory) {
                        walk(f)
                    }
                }
            }
        }
        walk(dir)
        return total
    }

    private data class CacheEntry(val bytes: Long, val stamp: Long)

    private const val TTL_MS = 60_000L
    private val sizeCache = ConcurrentHashMap<String, CacheEntry>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "redt-dirsize").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun cachedSize(dir: File): Long? =
        sizeCache[dir.absolutePath]
            ?.takeIf { System.currentTimeMillis() - it.stamp < TTL_MS }
            ?.bytes

    fun invalidate(dir: File) {
        sizeCache.remove(dir.absolutePath)
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

    fun invalidateAll() {
        sizeCache.clear()
    }

    /**
     * Runs [block] on the background size-computation executor. Useful for
     * callers (e.g. widget updates) that need to perform cheap file I/O off the
     * main thread without spinning up their own thread.
     */
    fun runOnBackground(block: () -> Unit) {
        executor.execute(block)
    }
}
