package com.redt.proot

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object ProotInstaller {
    private const val TAG = "ProotInstaller"
    private const val PROOT_BIN = "proot"
    private const val PROOT_LIB = "libproot.so"

    private fun zipEntryPath(): String = "lib/arm64-v8a/$PROOT_LIB"

    /**
     * Resolves the proot binary, trying three locations in order:
     * 1. nativeLibraryDir (system-extracted jniLibs),
     * 2. an earlier extraction in code_cache,
     * 3. a fresh extraction from the APK zip into code_cache.
     */
    fun getProotPath(context: Context): String? =
        prootFromNativeLibraryDir(context) ?: prootFromCache(context) ?: extractProotFromApk(context)

    private fun prootFromNativeLibraryDir(context: Context): String? {
        val nativePath = "${context.applicationInfo.nativeLibraryDir}/$PROOT_LIB"
        val nativeFile = File(nativePath)
        if (nativeFile.canExecute()) {
            Log.i(TAG, "Found proot at nativeLibraryDir: $nativePath")
            return nativePath
        }
        if (nativeFile.exists()) {
            nativeFile.setExecutable(true, false)
            if (nativeFile.canExecute()) return nativePath
        }
        return null
    }

    private fun prootFromCache(context: Context): String? {
        val cachePath = "${context.codeCacheDir}/$PROOT_BIN/$PROOT_BIN"
        if (!File(cachePath).canExecute()) return null
        Log.i(TAG, "Found proot at code_cache: $cachePath")
        return cachePath
    }

    private fun extractProotFromApk(context: Context): String? {
        val apkPath = context.applicationInfo.sourceDir
        Log.i(TAG, "Extracting proot from APK: $apkPath [entry=${zipEntryPath()}]")
        val destDir = File(context.codeCacheDir, PROOT_BIN)
        destDir.mkdirs()
        val cacheFile = File(destDir, PROOT_BIN)
        return try {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(zipEntryPath())
                if (entry == null) {
                    logAvailableLibEntries(zip)
                    return null
                }
                zip.getInputStream(entry).use { input ->
                    val tmpFile = File(destDir, "$PROOT_BIN.tmp")
                    FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
                    tmpFile.setReadable(true, false)
                    tmpFile.setWritable(false)
                    tmpFile.setExecutable(true, false)
                    cacheFile.delete()
                    if (tmpFile.renameTo(cacheFile)) {
                        cacheFile.setExecutable(true, false)
                        if (cacheFile.canExecute()) {
                            Log.i(TAG, "Extracted proot to: ${cacheFile.absolutePath}")
                            return cacheFile.absolutePath
                        }
                        Log.e(TAG, "Extracted but not executable: ${cacheFile.absolutePath}")
                        return null
                    }
                    // rename failed, try using the tmp file directly
                    return if (tmpFile.canExecute()) {
                        Log.i(TAG, "Using tmp file: ${tmpFile.absolutePath}")
                        tmpFile.absolutePath
                    } else null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract proot from APK", e)
            null
        }
    }

    private fun logAvailableLibEntries(zip: ZipFile) {
        val libEntries = zip.entries().asSequence()
            .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            .map { it.name }
            .toList()
        Log.e(TAG, "Available lib entries: $libEntries")
    }

    fun isInstalled(context: Context): Boolean {
        return getProotPath(context) != null
    }
}
