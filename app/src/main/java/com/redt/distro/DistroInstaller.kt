package com.redt.distro

import android.content.Context
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import com.redt.ui.Prefs
import com.redt.util.FileUtil
import com.redt.util.isUnder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class DistroInstaller(private val context: Context) {

    data class Progress(val percent: Int, val speed: String)

    private val generation = AtomicInteger(0)

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * Cancellation needs two cooperating mechanisms because installs run
     * blocking IO loops that never observe coroutine cancellation:
     * - [cancelled] stops the *current* job's loops ([cancel]) while still
     *   letting that job's cleanup run;
     * - the generation counter invalidates a *previous* job that keeps
     * draining on an IO thread after a retry already reset the flag.
     */
    private fun beginJob(): Int {
        cancelled = false
        return generation.incrementAndGet()
    }

    suspend fun install(
        distro: Distro,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val gen = beginJob()
        try {
            val rootfsDir = getRootfsDir(distro.name)
            if (rootfsDir.exists()) {
                deleteRootfsSafe(rootfsDir)
            }
            rootfsDir.mkdirs()

            val tarball = cachedTarballFile(distro)
            if (tarball.exists()) tarball.delete()
            val partial = File(tarballDir(), tarball.name + ".part")
            val tarballUrl = distro.tarballUrl
            Log.i("DistroInstaller", "Downloading $tarballUrl")

            downloadTarball(tarballUrl, tarball, partial, onProgress, gen)
            checkCancel(gen)

            val expectedSha = distro.sha256
            if (expectedSha.isNotEmpty()) {
                verifyChecksum(tarball, expectedSha)
            }
            checkCancel(gen)

            extractTarball(tarball, rootfsDir, onProgress, gen)
            checkCancel(gen)
            fixupDirectoryPermissions(rootfsDir)
            com.redt.util.Format.invalidate(rootfsDir)
            saveInstalled(distro.name)
            // The base tarball exists only to reset without re-downloading;
            // keeping it doubles disk usage (rootfs + compressed copy). Drop
            // it once the install is committed: reset falls back to a fresh
            // download, and the UI no longer exposes a tarball-cache control.
            if (!tarball.delete() && tarball.exists()) {
                Log.w("DistroInstaller", "Could not delete cached tarball ${tarball.name}")
            }
            Log.i("DistroInstaller", "Install complete for ${distro.name}")
        } catch (e: CancelledException) {
            Log.i("DistroInstaller", "Install cancelled for ${distro.name}")
            cleanup(distro, gen)
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            cleanup(distro, gen)
            throw e
        } catch (e: Exception) {
            Log.e("DistroInstaller", "Install failed", e)
            cleanup(distro, gen)
            throw e
        }
    }

    private fun tarballDir(): File =
        File(context.filesDir, "tarballs").apply { mkdirs() }

    private fun cachedTarballName(distro: Distro): String {
        val url = distro.tarballUrl
        return when {
            url.endsWith(".tar.gz", true) -> "${distro.name}.tar.gz"
            else -> "${distro.name}.tar"
        }
    }

    private fun cachedTarballFile(distro: Distro): File =
        File(tarballDir(), cachedTarballName(distro))

    class CancelledException : Exception("Installation cancelled")

    /**
     * Deletes a rootfs tree safely:
     * - runs on Dispatchers.IO regardless of caller,
     * - never follows symlinks (see [FileUtil]),
     * - tracks visited canonical paths so symlink cycles cannot loop forever.
     * Failures are logged, not thrown; returns false if any node could not
     * be removed.
     */
    suspend fun deleteRootfsSafe(dir: File): Boolean = withContext(Dispatchers.IO) {
        if (!dir.exists()) true else FileUtil.deleteTreeWithoutFollowingLinks(dir)
    }

    private fun checkCancel(gen: Int) {
        if (cancelled || generation.get() != gen) throw CancelledException()
    }

    private suspend fun cleanup(distro: Distro, gen: Int) {
        // A newer job now owns these paths and handles them itself; deleting
        // here would race its download/extraction.
        if (generation.get() != gen) return
        // Runs even when this coroutine is being cancelled: a cancelled
        // install must not leave a half-extracted rootfs behind. Plain
        // withContext(Dispatchers.IO) would hit the cancellation fast-path
        // and skip the delete entirely.
        withContext(NonCancellable) {
            if (runCatching { validateDistroName(distro.name) }.isFailure) return@withContext
            try {
                val dir = getRootfsDir(distro.name)
                deleteRootfsSafe(dir)
                com.redt.util.Format.invalidate(dir)
            } catch (_: Exception) {}
            try {
                cachedTarballFile(distro).delete()
                // The .part file is intentionally kept: a fresh attempt
                // resumes from it instead of restarting the download.
            } catch (_: Exception) {}
            try {
                File(context.filesDir, "installed/${distro.name}").delete()
            } catch (_: Exception) {}
            notifyDocumentRootsChanged()
        }
    }

    /** Atomically promotes a finished .part download to its final name. */
    private fun promotePartial(partial: File, dest: File) {
        if (partial.renameTo(dest)) return
        partial.copyTo(dest, overwrite = true)
        partial.delete()
    }

    private suspend fun downloadTarball(
        urlString: String,
        dest: File,
        partial: File,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) {
        val httpUrl = URL(urlString)
        val conn = httpUrl.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = Prefs.CONNECT_TIMEOUT_MS
            conn.readTimeout = Prefs.READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            var resumeOffset = 0L
            if (partial.exists()) {
                resumeOffset = partial.length()
                conn.setRequestProperty("Range", "bytes=$resumeOffset-")
            }
            conn.connect()

            val responseCode = conn.responseCode
            if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                // Server says the partial already covers the whole file:
                // promote it and let the caller's checksum verify it.
                promotePartial(partial, dest)
                return
            }
            val resuming = responseCode == HttpURLConnection.HTTP_PARTIAL && resumeOffset > 0
            if (responseCode != HttpURLConnection.HTTP_OK && !resuming) {
                throw Exception("HTTP $responseCode for $urlString")
            }
            if (!resuming) resumeOffset = 0

            val rangeLen = conn.contentLengthLong
            val total = if (rangeLen > 0) resumeOffset + rangeLen else -1L
            if (total > 0) {
                // Incoming bytes plus room for the extracted rootfs
                // (gz decompresses roughly ROOTFS_EXPANSION_FACTOR:1).
                requireFreeSpace((total - resumeOffset) + total * ROOTFS_EXPANSION_FACTOR, "installing ${dest.name}")
            }

            val buffer = ByteArray(8192)
            FileOutputStream(partial, resuming).use { output ->
                conn.inputStream.use { input ->
                    var downloaded = resumeOffset
                    var sessionBytes = 0L
                    val startTime = System.currentTimeMillis()
                    var lastEmit = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        checkCancel(gen)
                        output.write(buffer, 0, read)
                        downloaded += read
                        sessionBytes += read
                        val now = System.currentTimeMillis()
                        val finished = total > 0 && downloaded >= total
                        val throttled = now - lastEmit < PROGRESS_EMIT_INTERVAL_MS && !finished
                        if (throttled) continue
                        lastEmit = now
                        val elapsed = (now - startTime) / 1000
                        val speed = if (elapsed > 0) {
                            "${(sessionBytes / 1000 / elapsed)} KB/s"
                        } else "0 KB/s"
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            onProgress(Progress(percent, speed))
                        } else {
                            onProgress(Progress(-1, "${com.redt.util.Format.size(downloaded)} - $speed"))
                        }
                    }
                }
            }
            promotePartial(partial, dest)
        } finally {
            conn.disconnect()
        }
    }

    private fun verifyChecksum(file: File, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expectedSha256.lowercase()) {
            throw Exception("SHA-256 mismatch: expected $expectedSha256, got $actual")
        }
    }

    private fun requireFreeSpace(needed: Long, what: String) {
        if (needed <= 0) return
        val stat = StatFs(context.filesDir.absolutePath)
        val available = stat.availableBytes
        if (available < needed) {
            throw Exception(
                "Not enough free space for $what: need " +
                    "${com.redt.util.Format.size(needed)}, " +
                    "have ${com.redt.util.Format.size(available)}"
            )
        }
    }

    private suspend fun extractTarball(
        tarball: File,
        dest: File,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) = withContext(Dispatchers.IO) {
        if (!tarball.name.endsWith(".tar.gz", true)) {
            throw Exception("Unsupported archive format: ${tarball.name}")
        }
        extractWithJavaGz(tarball, dest, onProgress, gen)
    }

    /**
     * Wraps an InputStream and counts the bytes that have been read through it.
     * Used to compute extraction progress from the *compressed* byte position
     * (rather than the decompressed byte count, which produced meaningless
     * percentages because the denominator was an arbitrary 3x fudge factor).
     */
    private class CountingInputStream(private val inner: java.io.InputStream) : java.io.InputStream() {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val v = inner.read()
            if (v != -1) bytesRead++
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }

    private fun extractWithJavaGz(
        tarball: File, dest: File,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) {
        try {
            val total = tarball.length()
            FileInputStream(tarball).use { fis ->
                val counter = CountingInputStream(fis)
                GzipCompressorInputStream(counter).use { gzIn ->
                    BufferedInputStream(gzIn, 65536).use { bis ->
                        TarArchiveInputStream(bis).use { tarIn ->
                            extractTarEntries(tarIn, dest, total, onProgress, gen) { counter.bytesRead }
                        }
                    }
                }
            }
        } catch (e: NoClassDefFoundError) {
            throw Exception("Missing compression library: ${e.message}")
        }
    }

    private fun extractTarEntries(
        tarIn: TarArchiveInputStream, dest: File,
        totalForProgress: Long,
        onProgress: (Progress) -> Unit,
        gen: Int,
        bytesReadProvider: () -> Long
    ): Int {
        val firstEntry = tarIn.getNextEntry()
        var prefixToStrip = ""
        if (firstEntry != null) {
            val name = firstEntry.name
            val slash = name.indexOf('/')
            if (slash > 0) {
                prefixToStrip = name.substring(0, slash + 1)
                Log.i("DistroInstaller", "Stripping prefix: $prefixToStrip")
            }
        }
        val canonicalDest = dest.canonicalFile

        fun stripPrefix(name: String): String =
            if (prefixToStrip.isNotEmpty() && name.startsWith(prefixToStrip)) {
                name.removePrefix(prefixToStrip)
            } else {
                name
            }

        fun processEntry(entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry) {
            val entryName = stripPrefix(entry.name)
            // Root-dir marker entries ("./", ".") carry no content and resolve
            // to the destination itself; skip them instead of rejecting them
            // as "unsafe".
            if (entryName.isEmpty() || entryName == "." || entryName == "./") return
            val target = File(dest, entryName).canonicalFile
            if (target == canonicalDest || !target.isUnder(canonicalDest)) {
                throw Exception("Unsafe archive entry: ${entry.name}")
            }
            if (entry.isSymbolicLink) {
                val linkTarget = entry.linkName
                try {
                    // Absolute link targets are relative to the *guest* root
                    // (e.g. bin/arch -> /bin/busybox), not to the host filesystem.
                    // File.resolve() returns absolute children unchanged, so they
                    // must be joined under the rootfs manually.
                    val resolvedTarget = if (linkTarget.startsWith("/")) {
                        File(canonicalDest, linkTarget.substring(1)).canonicalFile
                    } else {
                        target.parentFile?.canonicalFile?.resolve(linkTarget)?.canonicalFile
                    }
                    val isSafe = resolvedTarget != null && resolvedTarget.isUnder(canonicalDest)
                    if (!isSafe) {
                        Log.w("DistroInstaller", "Skipping unsafe symlink ${entry.name} -> $linkTarget")
                    } else {
                        target.parentFile?.mkdirs()
                        target.delete()
                        android.system.Os.symlink(linkTarget, target.absolutePath)
                    }
                } catch (e: Exception) {
                    Log.w("DistroInstaller", "Symlink failed ${entry.name}: ${e.message}")
                }
            } else if (entry.isLink) {
                // Hard link: payload lives at linkName, extracted earlier in
                // this archive. Falling back to a copy keeps the content even
                // on filesystems where link(2) fails.
                val linkTarget = stripPrefix(entry.linkName)
                val source = if (linkTarget.isEmpty()) null else File(dest, linkTarget).canonicalFile
                if (source == null || !source.isUnder(canonicalDest) || !source.isFile) {
                    Log.w("DistroInstaller", "Skipping hard link ${entry.name} -> ${entry.linkName}")
                } else {
                    target.parentFile?.mkdirs()
                    target.delete()
                    try {
                        android.system.Os.link(source.absolutePath, target.absolutePath)
                    } catch (e: Exception) {
                        source.copyTo(target, overwrite = true)
                    }
                }
            } else if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val read = tarIn.read(buf)
                        if (read == -1) break
                        checkCancel(gen)
                        out.write(buf, 0, read)
                    }
                }
                val perm = entry.mode and 0x1FF
                val isExec = (perm and 0b001001001) != 0
                target.setReadable(true, false)
                target.setExecutable(isExec, false)
                target.setWritable(true, false)
            }
        }
        var count = 0
        if (firstEntry != null) {
            processEntry(firstEntry)
            count++
        }
        var lastEmit = 0L
        var entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry? = tarIn.getNextEntry()
        while (entry != null) {
            checkCancel(gen)
            processEntry(entry)
            count++
            val now = System.currentTimeMillis()
            if (now - lastEmit >= PROGRESS_EMIT_INTERVAL_MS) {
                lastEmit = now
                val pct = if (totalForProgress > 0) {
                    ((bytesReadProvider() * 100L) / totalForProgress).toInt().coerceIn(0, 99)
                } else 0
                onProgress(Progress(pct, "Extracting"))
            }
            entry = tarIn.getNextEntry()
        }
        return count
    }

    /**
     * The guest always gets this fixed public DNS pair. Device DNS is never
     * used: it breaks when the network changes and VPN apps (NetGuard,
     * Blokada, ...) can inject resolver addresses (198.18.0.0/15) that only
     * work while their tunnel is up.
     */
    private val guestDnsServers = listOf("8.8.8.8", "1.1.1.1")

    /**
     * True when [resolv] must be rewritten: missing/unreadable, or its
     * nameserver list differs from [guestDnsServers]. This covers baked
     * device DNS, VPN benchmark ranges, loopback stubs and manual edits.
     */
    private fun needsResolvRewrite(resolv: File): Boolean {
        val nameservers = try {
            resolv.readLines().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("nameserver")) trimmed.removePrefix("nameserver").trim() else null
            }
        } catch (_: Exception) {
            return true
        }
        return nameservers != guestDnsServers
    }

    private fun writeResolvConf(rootfs: File) {
        val resolv = File(rootfs, "etc/resolv.conf")
        if (!needsResolvRewrite(resolv)) return
        resolv.parentFile?.mkdirs()
        // A symlink (e.g. -> /run/resolvconf/resolv.conf) must be removed
        // first so writeText below cannot follow it out of the rootfs.
        if (FileUtil.isSymlink(resolv)) resolv.delete()
        safeWriteText(resolv, guestDnsServers.joinToString("") { "nameserver $it\n" })
    }

    private fun ensureSupplementaryGroups(rootfs: File) {
        val group = File(rootfs, "etc/group")
        group.parentFile?.mkdirs()
        val existingText = if (group.exists()) group.readText() else ""
        val existingNames = existingText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.substringBefore(':') }
            .toMutableSet()

        val additions = mutableListOf<String>()
        fun addEntry(line: String) {
            if (existingNames.add(line.substringBefore(':'))) additions.add(line)
        }

        listOf(
            "root:x:0:root", "wheel:x:0:root",
            "inet:x:3003:", "everybody:x:9997:"
        ).forEach(::addEntry)
        selfGroupIds().forEach { gid -> addEntry("android_$gid:x:$gid:") }

        if (additions.isEmpty()) return
        val sb = StringBuilder(existingText)
        if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
        additions.forEach { sb.append(it).append('\n') }
        safeWriteText(group, sb.toString())
    }

    /** Supplementary group IDs of this process, from /proc/self/status. */
    private fun selfGroupIds(): List<Int> = try {
        java.io.File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("Groups:") }
            ?.removePrefix("Groups:")
            ?.trim()
            ?.split("\\s+".toRegex())
            ?.mapNotNull { it.toIntOrNull() }
            ?.filter { it > 0 }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun ensureWritable(file: File) {
        if (file.exists()) file.setWritable(true, false)
        file.parentFile?.let { if (!it.canWrite()) it.setWritable(true, false) }
    }

    private fun safeWriteText(file: File, text: String) {
        ensureWritable(file)
        file.writeText(text)
    }

    private fun safeAppendText(file: File, text: String) {
        ensureWritable(file)
        file.appendText(text)
    }

    private fun fixupDirectoryPermissions(rootfs: File) {
        rootfs.walkTopDown().filter { it.isDirectory }.forEach { d ->
            d.setReadable(true, false)
            d.setExecutable(true, false)
            d.setWritable(true, true)
        }
    }

    fun repairRootfs(rootfs: File): String {
        val repairs = mutableListOf<String>()
        repairs += repairDirectoryPermissionsOnce(rootfs)
        repairs += repairPasswdEntry(rootfs)
        ensureSupplementaryGroups(rootfs)
        repairs += repairHosts(rootfs)
        val rootDir = File(rootfs, "root")
        if (!rootDir.exists() && rootDir.mkdirs()) repairs.add("Created /root")
        repairs += repairShell(rootfs)
        if (needsResolvRewrite(File(rootfs, "etc/resolv.conf"))) {
            writeResolvConf(rootfs)
            repairs.add("Created etc/resolv.conf")
        }
        return repairs.joinToString("\n")
    }

    private fun repairDirectoryPermissionsOnce(rootfs: File): List<String> {
        if (File(rootfs, ".perms_fixed").exists()) return emptyList()
        fixupDirectoryPermissions(rootfs)
        try {
            File(rootfs, ".perms_fixed").writeText("1")
        } catch (_: Exception) {}
        return listOf("Fixed directory permissions")
    }

    private fun repairPasswdEntry(rootfs: File): List<String> {
        val uid = android.os.Process.myUid()
        val passwd = File(rootfs, "etc/passwd")
        if (passwd.exists() && passwd.readText().contains(":$uid:")) return emptyList()
        passwd.parentFile?.mkdirs()
        safeAppendText(passwd, "root:x:$uid:0:root:/root:/bin/sh\n")
        return listOf("Added passwd entry for uid $uid")
    }

    private fun repairHosts(rootfs: File): List<String> {
        val hosts = File(rootfs, "etc/hosts")
        if (hosts.exists() && hosts.readText().contains("127.0.0.1")) return emptyList()
        hosts.parentFile?.mkdirs()
        safeWriteText(hosts, "127.0.0.1 localhost\n::1 localhost\n")
        return listOf("Created /etc/hosts")
    }

    /** Makes bin/sh usable, preferring a busybox symlink over a copy. */
    private fun repairShell(rootfs: File): List<String> {
        val repairs = mutableListOf<String>()
        val busybox = File(rootfs, "bin/busybox")
        if (!busybox.exists()) {
            repairs.add("WARN: bin/busybox not found in rootfs")
            val binDir = File(rootfs, "bin")
            repairs.add(
                if (binDir.exists()) {
                    "bin/ contents: ${binDir.list()?.joinToString(", ") ?: "empty"}"
                } else {
                    "bin/ directory missing!"
                }
            )
            return repairs
        }
        if (!busybox.canExecute()) {
            busybox.setExecutable(true, false)
            repairs.add("Made bin/busybox executable")
        }
        val sh = File(rootfs, "bin/sh")
        if (sh.exists() && sh.canExecute()) return repairs
        sh.delete()
        try {
            android.system.Os.symlink("busybox", sh.absolutePath)
        } catch (_: Exception) {
            busybox.copyTo(sh, overwrite = true)
            sh.setExecutable(true, false)
            repairs.add("Copied bin/busybox -> bin/sh")
        }
        repairs.add(
            if (sh.canExecute()) "bin/sh is now executable"
            else "WARN: bin/sh still not executable"
        )
        return repairs
    }

    fun getRootfsDir(distroName: String): File {
        val validName = validateDistroName(distroName)

        val rootfsParent = File(context.filesDir, "rootfs").canonicalFile
        val rootfsDir = File(rootfsParent, validName).canonicalFile
        require(rootfsDir.parentFile == rootfsParent) { "Invalid distro path" }
        return rootfsDir
    }

    fun saveInstalled(distroName: String) {
        val validName = validateDistroName(distroName)
        File(context.filesDir, "installed").mkdirs()
        File(context.filesDir, "installed/$validName").writeText(validName)
        notifyDocumentRootsChanged()
    }

    private fun validateDistroName(distroName: String): String {
        require(distroName.isNotBlank()) { "Distro name cannot be empty" }
        require(distroName == distroName.trim()) { "Invalid distro name" }
        require(distroName != "." && distroName != "..") { "Invalid distro name" }
        require('/' !in distroName && '\\' !in distroName && '\u0000' !in distroName) {
            "Invalid distro name"
        }
        return distroName
    }

    /**
     * Startup cleanup for disk usage not owned by any visible feature:
     * - rootfs dirs without an "installed" marker (husk of an interrupted
     *   uninstall) that are at least a day old, so an in-flight install (which
     *   also has no marker yet) is never touched;
     * - download files (.part resume fragments and stray tarballs) older than
     *   a week.
     */
    suspend fun sweepOrphanFiles() = withContext(Dispatchers.IO) {
        val dayMs = 24L * 60 * 60 * 1000
        val huskCutoff = System.currentTimeMillis() - dayMs
        val partialCutoff = System.currentTimeMillis() - 7 * dayMs
        val rootfsParent = File(context.filesDir, "rootfs").canonicalFile
        rootfsParent.listFiles()?.forEach { dir ->
            if (dir.isDirectory && !FileUtil.isSymlink(dir) &&
                !File(context.filesDir, "installed/${dir.name}").exists() &&
                dir.lastModified() < huskCutoff
            ) {
                Log.i("DistroInstaller", "Sweeping orphan rootfs ${dir.name}")
                deleteRootfsSafe(dir)
            }
        }
        tarballDir().listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < partialCutoff) {
                Log.i("DistroInstaller", "Sweeping stale download file ${f.name}")
                f.delete()
            }
        }
    }

    private companion object {
        const val PROGRESS_EMIT_INTERVAL_MS = 100L

        /**
         * Room reserved for the extracted rootfs on top of the tarball:
         * gzip-compressed rootfs archives decompress roughly 2:1.
         */
        private const val ROOTFS_EXPANSION_FACTOR = 2L

        /**
         * java.net.HttpURLConnection on Android does not define
         * HTTP_REQUESTED_RANGE_NOT_SATISFIABLE (OpenJDK only), so the 416
         * status code is spelled out here.
         */
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }

    private fun notifyDocumentRootsChanged() {
        val authority = "${context.packageName}.documents"
        context.contentResolver.notifyChange(
            DocumentsContract.buildRootsUri(authority),
            null,
        )
        context.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(authority, "root"),
            null,
        )
    }
}
