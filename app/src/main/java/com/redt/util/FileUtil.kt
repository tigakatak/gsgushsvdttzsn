package com.redt.util

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

/**
 * Shared lstat-based filesystem helpers. Everything here treats a symlink as
 * a leaf: links are never followed, so guest rootfs symlinks (absolute link
 * targets such as bin/arch -> /bin/busybox) can never redirect a delete or
 * a size walk outside the tree being operated on.
 */
object FileUtil {
    private const val TAG = "FileUtil"

    /** True when [file] itself is a symlink (lstat-based, never follows it). */
    fun isSymlink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
    } catch (_: Exception) {
        false
    }

    /** True when the path exists without following a trailing symlink. */
    fun existsWithoutFollowingLinks(file: File): Boolean = try {
        Os.lstat(file.absolutePath)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Deletes a file or directory tree safely:
     * - never follows symlinks (lstat-based),
     * - tracks visited canonical paths so a pathological link/hardlink cycle
     *   cannot loop forever.
     * Failures are logged, not thrown; returns false if any node could not
     * be removed.
     */
    fun deleteTreeWithoutFollowingLinks(file: File): Boolean {
        val visited = mutableSetOf<String>()
        var failed = false

        fun deleteNode(f: File) {
            val canonical = try {
                f.canonicalPath
            } catch (_: Exception) {
                f.absolutePath
            }
            if (!visited.add(canonical)) return
            if (isSymlink(f) || !f.isDirectory) {
                if (!f.delete() && existsWithoutFollowingLinks(f)) {
                    failed = true
                    Log.w(TAG, "Failed to delete ${f.absolutePath}")
                }
                return
            }
            f.listFiles()?.forEach { deleteNode(it) }
            if (!f.delete() && existsWithoutFollowingLinks(f)) {
                failed = true
                Log.w(TAG, "Failed to delete dir ${f.absolutePath}")
            }
        }

        deleteNode(file)
        return !failed
    }
}

/**
 * True when [this] lies strictly underneath [ancestor] (pure path check;
 * caller decides whether equality with the ancestor counts as inside).
 */
fun File.isUnder(ancestor: File): Boolean =
    path.startsWith(ancestor.path + File.separator)
