package com.redt.distro

import android.content.Context
import android.os.StatFs
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.redt.ui.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
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
    var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * Starts a new job: clears the cancel flag and bumps the generation so a
     * still-draining previous job fails its next [checkCancel] even though
     * the shared [cancelled] flag has been reset.
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
            val tarballUrl = distro.tarballUrl()
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
            cleanup(distro.name, gen)
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            cleanup(distro.name, gen)
            throw e
        } catch (e: Exception) {
            Log.e("DistroInstaller", "Install failed", e)
            cleanup(distro.name, gen)
            throw Exception("Install failed: ${e.message}", e)
        }
    }

    private fun tarballDir(): File =
        File(context.filesDir, "tarballs").apply { mkdirs() }

    private fun cachedTarballName(distro: Distro): String {
        val url = distro.tarballUrl()
        return when {
            url.endsWith(".tar.xz", true) -> "${distro.name}.tar.xz"
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
     * - never follows symlinks (lstat-based, like
     *   RedTDocumentsProvider.deleteWithoutFollowingLinks),
     * - tracks visited canonical paths so symlink cycles cannot loop forever.
     * Failures are logged, not thrown; returns false if any node could not be
     * removed.
     */
    suspend fun deleteRootfsSafe(dir: File): Boolean = withContext(Dispatchers.IO) {
        if (!dir.exists()) return@withContext true
        val visited = mutableSetOf<String>()
        var failed = false

        fun deleteNode(f: File) {
            val canonical = try {
                f.canonicalPath
            } catch (_: Exception) {
                f.absolutePath
            }
            if (!visited.add(canonical)) return
            val isLink = try {
                OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode)
            } catch (_: Exception) {
                false
            }
            if (isLink || !f.isDirectory) {
                if (!f.delete() && existsRegardlessOfLink(f)) {
                    failed = true
                    Log.w("DistroInstaller", "Failed to delete ${f.absolutePath}")
                }
                return
            }
            f.listFiles()?.forEach { deleteNode(it) }
            if (!f.delete() && existsRegardlessOfLink(f)) {
                failed = true
                Log.w("DistroInstaller", "Failed to delete dir ${f.absolutePath}")
            }
        }

        deleteNode(dir)
        !failed
    }

    private fun existsRegardlessOfLink(f: File): Boolean = try {
        val stat = Os.lstat(f.absolutePath)
        stat.st_mode != 0
    } catch (_: Exception) {
        f.exists()
    }

    private fun checkCancel(gen: Int) {
        if (cancelled || generation.get() != gen) throw CancelledException()
    }

    private suspend fun cleanup(distroName: String, gen: Int) {
        // A newer job now owns these paths and handles them itself; deleting
        // here would race its download/extraction.
        if (generation.get() != gen) return
        // Runs even when this coroutine is being cancelled: a cancelled
        // install must not leave a half-extracted rootfs behind. Plain
        // withContext(Dispatchers.IO) would hit the cancellation fast-path
        // and skip the delete entirely.
        withContext(NonCancellable) {
            if (runCatching { validateDistroName(distroName) }.isFailure) return@withContext
            try {
                val dir = getRootfsDir(distroName)
                deleteRootfsSafe(dir)
                com.redt.util.Format.invalidate(dir)
            } catch (_: Exception) {}
            try {
                val distro = DistroRegistry.allDistros.firstOrNull { it.name == distroName }
                val cached = if (distro != null) cachedTarballFile(distro)
                else File(tarballDir(), "$distroName.tar.xz")
                cached.delete()
                // The .part file is intentionally kept: a fresh attempt
                // resumes from it instead of restarting the download.
            } catch (_: Exception) {}
            try {
                File(context.filesDir, "installed/$distroName").delete()
            } catch (_: Exception) {}
            notifyDocumentRootsChanged()
        }
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
                if (!partial.renameTo(dest)) {
                    partial.copyTo(dest, overwrite = true)
                    partial.delete()
                }
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
                // (xz decompresses roughly 3:1, gz roughly 2:1).
                val expansion = if (dest.name.endsWith(".tar.xz", true)) 3L else 2L
                requireFreeSpace((total - resumeOffset) + total * expansion, "installing ${dest.name}")
            }

            val buffer = ByteArray(8192)
            FileOutputStream(partial, resuming).use { output ->
                conn.inputStream.use { input ->
                    var read: Int
                    var downloaded = resumeOffset
                    var sessionBytes = 0L
                    val startTime = System.currentTimeMillis()
                    var lastEmit = 0L

                    while (input.read(buffer).also { read = it } != -1) {
                        checkCancel(gen)
                        output.write(buffer, 0, read)
                        downloaded += read
                        sessionBytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit < PROGRESS_EMIT_INTERVAL_MS &&
                            (total <= 0 || downloaded < total)
                        ) {
                            continue
                        }
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
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun verifyChecksum(file: File, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expectedSha256.lowercase()) {
            throw Exception("SHA-256 mismatch: expected $expectedSha256, got $actual")
        }
    }

    /** Rough decompressed size: xz ~3:1, gz ~2:1. */
    private fun extractionSpaceFor(file: File): Long =
        file.length() * (if (file.name.endsWith(".tar.xz", true)) 3L else 2L)

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

    private fun getNativeXz(): File? {
        val abis = android.os.Build.SUPPORTED_64_BIT_ABIS
        if (abis.isEmpty() || abis[0] != "arm64-v8a") return null
        val xzDir = File(context.codeCacheDir, "xz")
        val xzBin = File(xzDir, "xz")
        val xzLib = File(xzDir, "liblzma.so.5")
        if (xzBin.canExecute() && xzLib.canRead()) return xzBin
        try {
            xzDir.mkdirs()
            context.assets.open("xz/lib/liblzma.so.5").use { input ->
                FileOutputStream(xzLib).use { input.copyTo(it) }
            }
            xzLib.setReadable(true, false)
            context.assets.open("xz/bin/xz").use { input ->
                FileOutputStream(xzBin).use { input.copyTo(it) }
            }
            xzBin.setReadable(true, false)
            xzBin.setExecutable(true, false)
            if (xzBin.canExecute()) return xzBin
        } catch (e: Exception) {
            Log.w("DistroInstaller", "Native xz not available", e)
            xzBin.delete()
            xzLib.delete()
        }
        return null
    }

    private suspend fun extractTarball(
        tarball: File,
        dest: File,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) = withContext(Dispatchers.IO) {
        if (tarball.name.endsWith(".tar.gz", true)) {
            extractWithJavaGz(tarball, dest, onProgress, gen)
            return@withContext
        }
        val nativeXz = getNativeXz()
        if (nativeXz != null) {
            try {
                extractWithNativeXz(nativeXz, tarball, dest, onProgress, gen)
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                Log.w("DistroInstaller", "Native xz failed, falling back to Java", e)
                extractWithJavaXz(tarball, dest, onProgress, gen)
            }
        } else {
            extractWithJavaXz(tarball, dest, onProgress, gen)
        }
    }

    private fun extractWithNativeXz(
        xzBin: File, tarball: File, dest: File,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) {
        val pb = ProcessBuilder(xzBin.absolutePath, "-dc", tarball.absolutePath)
        pb.environment()["LD_LIBRARY_PATH"] = xzBin.parentFile!!.absolutePath
        pb.redirectErrorStream(true)
        val process = pb.start()
        // Watchdog: native xz runs outside the coroutine; if it stalls the
        // blocking read below never reaches checkCancel. Kill it so the Java
        // fallback takes over instead of hanging the install forever.
        val watchdog = Thread {
            try {
                if (!process.waitFor(NATIVE_XZ_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                    killProcess(process)
                }
            } catch (_: Exception) {
                // best-effort; nothing else to clean up
            }
        }
        watchdog.isDaemon = true
        watchdog.start()
        try {
            var streamCompleted = false
            var entryCount = 0
            process.inputStream.use { input ->
                val counter = CountingInputStream(input)
                BufferedInputStream(counter, 65536).use { bis ->
                    TarArchiveInputStream(bis).use { tarIn ->
                        // Native xz reads the compressed file itself; the only byte
                        // stream we can observe here is the *decompressed* output, so
                        // fall back to a rough 3:1 ratio (xz typically achieves 3-5:1).
                        val approxDecompressedTotal = tarball.length() * 3L
                        entryCount = extractTarEntries(tarIn, dest, approxDecompressedTotal, onProgress, gen) { counter.bytesRead }
                    }
                }
                streamCompleted = true
            }
            if (!process.waitFor(NATIVE_XZ_EXIT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                killProcess(process)
                throw Exception("Native xz decompressor timed out, falling back")
            }
            val exitCode = process.exitValue()
            if (exitCode != 0 && (!streamCompleted || entryCount == 0)) {
                throw Exception("Native xz decompressor failed (exit $exitCode), falling back")
            }
            if (exitCode != 0) {
                Log.w("DistroInstaller", "xz exited with $exitCode after valid output; continuing")
            }
        } catch (e: CancelledException) {
            killProcess(process)
            throw e
        } catch (e: Exception) {
            killProcess(process)
            throw e
        }
    }

    private fun killProcess(process: Process) {
        process.destroy()
        try {
            process.destroyForcibly().waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            // best-effort; the SIGTERM from destroy() is still in flight
        }
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

    private fun extractWithJavaXz(
        tarball: File, dest: File,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) {
        try {
            val total = tarball.length()
            FileInputStream(tarball).use { fis ->
                val counter = CountingInputStream(fis)
                XZCompressorInputStream(counter).use { xzIn ->
                    BufferedInputStream(xzIn, 65536).use { bis ->
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

        fun isInsideDest(f: File): Boolean =
            f.path.startsWith(canonicalDest.path + File.separator)

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
            if (target == canonicalDest || !isInsideDest(target)) {
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
                    val isSafe = resolvedTarget != null && isInsideDest(resolvedTarget)
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
                if (source == null || !isInsideDest(source) || !source.isFile) {
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

    private fun isSymbolicLink(f: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode)
    } catch (_: Exception) {
        false
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
        if (isSymbolicLink(resolv)) resolv.delete()
        safeWriteText(resolv, guestDnsServers.joinToString("") { "nameserver $it\n" })
    }

    private fun ensureSupplementaryGroups(rootfs: File) {
        val group = File(rootfs, "etc/group")
        group.parentFile?.mkdirs()
        val existing = if (group.exists()) group.readText() else ""
        val existingNames = existing.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.substringBefore(':') }
            .toMutableSet()
        val sb = StringBuilder(existing)
        var changed = false
        val baseEntries = listOf(
            "root:x:0:root", "wheel:x:0:root",
            "inet:x:3003:", "everybody:x:9997:"
        )
        for (entry in baseEntries) {
            val name = entry.substringBefore(':')
            if (existingNames.add(name)) {
                if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
                sb.append(entry).append('\n')
                changed = true
            }
        }
        try {
            val status = java.io.File("/proc/self/status").readLines()
            val groupsLine = status.firstOrNull { it.startsWith("Groups:") }
            if (groupsLine != null) {
                val gids = groupsLine.removePrefix("Groups:").trim().split("\\s+".toRegex())
                for (gidStr in gids) {
                    val gid = gidStr.toIntOrNull() ?: continue
                    if (gid <= 0) continue
                    val name = "android_$gid"
                    if (existingNames.add(name)) {
                        if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
                        sb.append("$name:x:$gid:\n")
                        changed = true
                    }
                }
            }
        } catch (_: Exception) {}
        if (changed) safeWriteText(group, sb.toString())
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

    suspend fun setupRootfs(rootfs: File) {
        migrateNestedRootfs(rootfs)
        val uid = android.os.Process.myUid()
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.exists() || !passwd.readText().contains(":$uid:")) {
            passwd.parentFile?.mkdirs()
            safeAppendText(passwd, "root:x:$uid:0:root:/root:/bin/sh\n")
        }
        ensureSupplementaryGroups(rootfs)
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists() || !hosts.readText().contains("127.0.0.1")) {
            hosts.parentFile?.mkdirs()
            safeWriteText(hosts, "127.0.0.1 localhost\n::1 localhost\n")
        }
        writeResolvConf(rootfs)
        val fstab = File(rootfs, "etc/fstab")
        if (!fstab.exists()) {
            safeWriteText(fstab, "none /proc proc defaults 0 0\nnone /sys sysfs defaults 0 0\n")
        }
        createDeviceNodes(rootfs)
        repairRootfs(rootfs)
    }

    /**
     * One-time fixup (install time only): some tarballs wrap the whole rootfs
     * in a single top-level directory (e.g. <name>-rootfs/). Only migrate when
     * the root itself has no bin/ layout and exactly one subdirectory holds it.
     */
    private suspend fun migrateNestedRootfs(rootfs: File) {
        if (File(rootfs, "bin").isDirectory) return
        val subdirs = rootfs.listFiles()?.filter { it.isDirectory } ?: return
        if (subdirs.size != 1) return
        val subdir = subdirs.single()
        if (!File(subdir, "bin").isDirectory) return
        Log.i("DistroInstaller", "Found nested rootfs in ${subdir.name}/, migrating...")
        var copyOk = true
        subdir.listFiles()?.forEach { file ->
            val dest = File(rootfs, file.name)
            if (file.isDirectory) {
                if (!file.copyRecursively(dest, overwrite = true)) copyOk = false
            } else {
                try {
                    file.copyTo(dest, overwrite = true)
                } catch (_: Exception) {
                    copyOk = false
                }
            }
        }
        if (copyOk) {
            deleteRootfsSafe(subdir)
            Log.i("DistroInstaller", "Migrated files from ${subdir.name}/ to rootfs")
        } else {
            Log.w("DistroInstaller", "WARN: Failed to fully migrate ${subdir.name}/")
        }
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
        val uid = android.os.Process.myUid()

        if (!File(rootfs, ".perms_fixed").exists()) {
            fixupDirectoryPermissions(rootfs)
            try {
                File(rootfs, ".perms_fixed").writeText("1")
            } catch (_: Exception) {}
            repairs.add("Fixed directory permissions")
        }

        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.exists() || !passwd.readText().contains(":$uid:")) {
            passwd.parentFile?.mkdirs()
            safeAppendText(passwd, "root:x:$uid:0:root:/root:/bin/sh\n")
            repairs.add("Added passwd entry for uid $uid")
        }
        ensureSupplementaryGroups(rootfs)
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists() || !hosts.readText().contains("127.0.0.1")) {
            hosts.parentFile?.mkdirs()
            safeWriteText(hosts, "127.0.0.1 localhost\n::1 localhost\n")
            repairs.add("Created /etc/hosts")
        }

        File(rootfs, "root").mkdirs()
        repairs.add("Created /root")

        val busybox = File(rootfs, "bin/busybox")
        if (busybox.exists()) {
            if (!busybox.canExecute()) {
                busybox.setExecutable(true, false)
                repairs.add("Made bin/busybox executable")
            }
            val sh = File(rootfs, "bin/sh")
            if (!sh.exists() || !sh.canExecute()) {
                sh.delete()
                try {
                    android.system.Os.symlink("busybox", sh.absolutePath)
                } catch (_: Exception) {
                    busybox.copyTo(sh, overwrite = true)
                    sh.setExecutable(true, false)
                    repairs.add("Copied bin/busybox -> bin/sh")
                }
                if (sh.canExecute()) {
                    repairs.add("bin/sh is now executable")
                } else {
                    repairs.add("WARN: bin/sh still not executable")
                }
            }
        } else {
            repairs.add("WARN: bin/busybox not found in rootfs")
            val binDir = File(rootfs, "bin")
            if (binDir.exists()) {
                val contents = binDir.list()?.joinToString(", ") ?: "empty"
                repairs.add("bin/ contents: $contents")
            } else {
                repairs.add("bin/ directory missing!")
            }
        }

        val resolv = File(rootfs, "etc/resolv.conf")
        if (needsResolvRewrite(resolv)) {
            writeResolvConf(rootfs)
            repairs.add("Created etc/resolv.conf")
        }

        return repairs.joinToString("\n")
    }

    fun isInstalled(distroName: String): Boolean =
        File(context.filesDir, "installed/${validateDistroName(distroName)}").exists()

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

    fun getInstalledDistros(): List<String> {
        val dir = File(context.filesDir, "installed")
        if (!dir.exists()) return emptyList()
        return dir.list()
            ?.filter { runCatching { getRootfsDir(it).isDirectory }.getOrDefault(false) }
            ?.toList()
            ?: emptyList()
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
            if (dir.isDirectory && !isSymbolicLink(dir) &&
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
        const val NATIVE_XZ_TIMEOUT_SECONDS = 300L
        const val NATIVE_XZ_EXIT_TIMEOUT_SECONDS = 60L
        const val PROGRESS_EMIT_INTERVAL_MS = 100L

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

    private fun createDeviceNodes(rootfs: File) {
        val devDir = File(rootfs, "dev")
        devDir.mkdirs()
    }
}
