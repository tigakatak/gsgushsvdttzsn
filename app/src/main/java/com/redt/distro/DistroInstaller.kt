package com.redt.distro

import android.content.Context
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.redt.ui.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class DistroInstaller(private val context: Context) {

    data class Progress(val percent: Int, val speed: String)

    @Volatile
    var cancelled = false

    fun cancel() {
        cancelled = true
    }

    suspend fun install(
        distro: Distro,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        cancelled = false
        try {
            val rootfsDir = getRootfsDir(distro.name)
            if (rootfsDir.exists()) {
                deleteRootfsSafe(rootfsDir)
            }
            rootfsDir.mkdirs()

            val tarball = cachedTarballFile(distro)
            if (tarball.exists()) tarball.delete()
            val tarballUrl = distro.tarballUrl()
            Log.i("DistroInstaller", "Downloading $tarballUrl")

            downloadTarball(tarballUrl, tarball, onProgress)
            checkCancel()

            val expectedSha = distro.sha256
            if (expectedSha.isNotEmpty()) {
                verifyChecksum(tarball, expectedSha)
            }
            checkCancel()

            extractTarball(tarball, rootfsDir, onProgress)
            checkCancel()
            fixupDirectoryPermissions(rootfsDir)
            setupRootfs(rootfsDir)
            com.redt.util.Format.invalidate(rootfsDir)
            saveInstalled(distro.name)
            Log.i("DistroInstaller", "Install complete for ${distro.name}")
        } catch (e: CancelledException) {
            Log.i("DistroInstaller", "Install cancelled for ${distro.name}")
            cleanup(distro.name)
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            cleanup(distro.name)
            throw e
        } catch (e: Exception) {
            Log.e("DistroInstaller", "Install failed", e)
            cleanup(distro.name)
            throw Exception("Install failed: ${e.message}", e)
        }
    }

    suspend fun installFromFile(
        tarball: File,
        distroName: String,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        cancelled = false
        try {
            val rootfsDir = getRootfsDir(distroName)
            if (rootfsDir.exists()) {
                deleteRootfsSafe(rootfsDir)
            }
            rootfsDir.mkdirs()

            if (tarball.name.endsWith(".tar.xz")) {
                extractTarball(tarball, rootfsDir, onProgress)
            } else {
                extractWithJavaGz(tarball, rootfsDir, onProgress)
            }
            checkCancel()

            if (!File(rootfsDir, "etc/os-release").exists() &&
                !File(rootfsDir, "bin/busybox").exists()
            ) {
                throw Exception("File does not look like a Linux rootfs")
            }

            fixupDirectoryPermissions(rootfsDir)
            setupRootfs(rootfsDir)
            com.redt.util.Format.invalidate(rootfsDir)
            saveInstalled(distroName)
            Log.i("DistroInstaller", "Install from file complete for $distroName")
        } catch (e: CancelledException) {
            Log.i("DistroInstaller", "Install from file cancelled for $distroName")
            cleanup(distroName)
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            cleanup(distroName)
            throw e
        } catch (e: Exception) {
            Log.e("DistroInstaller", "Install from file failed", e)
            cleanup(distroName)
            throw Exception("Install from file failed: ${e.message}", e)
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

    /**
     * Restores a distro to its freshly extracted state: wipes installed
     * packages, caches and shell configs. Uses the cached base tarball when
     * available, otherwise falls back to a fresh download.
     */
    suspend fun resetToDefault(
        distroName: String,
        onProgress: (Progress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        cancelled = false
        val distro = com.redt.distro.DistroRegistry.allDistros
            .firstOrNull { it.name == distroName }
        if (distro == null) return@withContext false
        try {
            val rootfsDir = getRootfsDir(distroName)
            if (rootfsDir.exists()) {
                deleteRootfsSafe(rootfsDir)
            }
            rootfsDir.mkdirs()

            val tarball = cachedTarballFile(distro)
            if (!tarball.exists()) {
                install(distro, onProgress)
                return@withContext true
            }
            val expectedSha = distro.sha256
            if (expectedSha.isNotEmpty()) {
                verifyChecksum(tarball, expectedSha)
            }
            checkCancel()
            extractTarball(tarball, rootfsDir, onProgress)
            checkCancel()
            fixupDirectoryPermissions(rootfsDir)
            setupRootfs(rootfsDir)
            com.redt.util.Format.invalidate(rootfsDir)
            saveInstalled(distroName)
            Log.i("DistroInstaller", "Reset complete for $distroName")
            true
        } catch (e: CancelledException) {
            cleanup(distroName)
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            cleanup(distroName)
            throw e
        } catch (e: Exception) {
            Log.e("DistroInstaller", "Reset failed", e)
            cleanup(distroName)
            throw Exception("Reset failed: ${e.message}", e)
        }
    }

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

    private fun checkCancel() {
        if (cancelled) throw CancelledException()
    }

    private suspend fun cleanup(distroName: String) {
        if (runCatching { validateDistroName(distroName) }.isFailure) return
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
        } catch (_: Exception) {}
        try {
            File(context.filesDir, "installed/$distroName").delete()
        } catch (_: Exception) {}
        notifyDocumentRootsChanged()
    }

    private suspend fun downloadTarball(
        urlString: String,
        dest: File,
        onProgress: (Progress) -> Unit
    ) {
        val httpUrl = URL(urlString)
        val conn = httpUrl.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = Prefs.CONNECT_TIMEOUT_MS
            conn.readTimeout = Prefs.READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.connect()

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP $responseCode for $urlString")
            }

            val total = conn.contentLengthLong
            val buffer = ByteArray(8192)

            FileOutputStream(dest).use { output ->
                conn.inputStream.use { input ->
                    var read: Int
                    var downloaded = 0L
                    val startTime = System.currentTimeMillis()

                    while (input.read(buffer).also { read = it } != -1) {
                        checkCancel()
                        output.write(buffer, 0, read)
                        downloaded += read
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000
                        val speed = if (elapsed > 0) {
                            "${(downloaded / 1000 / elapsed)} KB/s"
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
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (tarball.name.endsWith(".tar.gz", true)) {
            extractWithJavaGz(tarball, dest, onProgress)
            return@withContext
        }
        val nativeXz = getNativeXz()
        if (nativeXz != null) {
            try {
                extractWithNativeXz(nativeXz, tarball, dest, onProgress)
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                Log.w("DistroInstaller", "Native xz failed, falling back to Java", e)
                extractWithJavaXz(tarball, dest, onProgress)
            }
        } else {
            extractWithJavaXz(tarball, dest, onProgress)
        }
    }

    private fun extractWithNativeXz(
        xzBin: File, tarball: File, dest: File,
        onProgress: (Progress) -> Unit
    ) {
        val pb = ProcessBuilder(xzBin.absolutePath, "-dc", tarball.absolutePath)
        pb.environment()["LD_LIBRARY_PATH"] = xzBin.parentFile!!.absolutePath
        pb.redirectErrorStream(true)
        val process = pb.start()
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
                        entryCount = extractTarEntries(tarIn, dest, approxDecompressedTotal, onProgress) { counter.bytesRead }
                    }
                }
                streamCompleted = true
            }
            val exitCode = process.waitFor()
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
        onProgress: (Progress) -> Unit
    ) {
        try {
            val total = tarball.length()
            FileInputStream(tarball).use { fis ->
                val counter = CountingInputStream(fis)
                XZCompressorInputStream(counter).use { xzIn ->
                    BufferedInputStream(xzIn, 65536).use { bis ->
                        TarArchiveInputStream(bis).use { tarIn ->
                            extractTarEntries(tarIn, dest, total, onProgress) { counter.bytesRead }
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
        onProgress: (Progress) -> Unit
    ) {
        try {
            val total = tarball.length()
            FileInputStream(tarball).use { fis ->
                val counter = CountingInputStream(fis)
                GzipCompressorInputStream(counter).use { gzIn ->
                    BufferedInputStream(gzIn, 65536).use { bis ->
                        TarArchiveInputStream(bis).use { tarIn ->
                            extractTarEntries(tarIn, dest, total, onProgress) { counter.bytesRead }
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
                        checkCancel()
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
        var entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry? = tarIn.getNextEntry()
        while (entry != null) {
            checkCancel()
            processEntry(entry)
            count++
            val pct = if (totalForProgress > 0) {
                ((bytesReadProvider() * 100L) / totalForProgress).toInt().coerceIn(0, 99)
            } else 0
            onProgress(Progress(pct, "Extracting"))
            entry = tarIn.getNextEntry()
        }
        return count
    }

    private fun isSymbolicLink(f: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode)
    } catch (_: Exception) {
        false
    }

    private fun writeResolvConf(rootfs: File) {
        val resolv = File(rootfs, "etc/resolv.conf")
        if (resolv.exists() && resolv.length() > 0L) return
        resolv.parentFile?.mkdirs()
        // A dangling symlink (e.g. -> /run/resolvconf/resolv.conf) reports
        // exists() == false; remove the link itself so writeText below cannot
        // follow it out of the rootfs.
        if (isSymbolicLink(resolv)) resolv.delete()
        safeWriteText(resolv, systemDnsServers().joinToString("") { "nameserver $it\n" })
    }

    private fun systemDnsServers(): List<String> {
        try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            if (network != null) {
                val dns = cm.getLinkProperties(network)?.dnsServers
                    ?.filter { !it.isAnyLocalAddress && !it.isLoopbackAddress }
                    ?.map { it.hostAddress }
                    ?.filter { !it.isNullOrEmpty() }
                if (!dns.isNullOrEmpty()) return dns
            }
        } catch (_: Exception) {}
        val hostResolv = try {
            File("/etc/resolv.conf").readLines().mapNotNull { line ->
                line.trim().removePrefix("nameserver").trim()
                    .takeIf { it.isNotEmpty() && it != "0.0.0.0" && it != "::" }
            }
        } catch (_: Exception) {
            emptyList()
        }
        return hostResolv.ifEmpty { listOf("8.8.8.8") }
    }

    private fun ensureCaCertificates(rootfs: File): String? {
        val bundlePaths = listOf(
            "etc/pki/tls/certs/ca-bundle.crt",
            "etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
            "etc/ssl/certs/ca-certificates.crt",
            "etc/ssl/cert.pem",
            "etc/ssl/certs/ca-bundle.crt"
        )
        val missing = bundlePaths.mapNotNull { path ->
            val f = File(rootfs, path)
            if (!f.exists() || f.length() == 0L) path else null
        }
        if (missing.isEmpty()) return null

        val androidCertDirs = listOf(
            File("/system/etc/security/cacerts"),
            File("/apex/com.android.conscrypt/cacerts")
        )
        val certs = StringBuilder()
        for (certDir in androidCertDirs) {
            if (!certDir.isDirectory) continue
            certDir.listFiles()?.forEach { certFile ->
                if (certFile.isFile) {
                    try {
                        val content = certFile.readText()
                        if (content.contains("BEGIN CERTIFICATE")) {
                            certs.append(content)
                            if (!content.endsWith("\n")) certs.append("\n")
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        if (certs.isEmpty()) return null

        val written = mutableListOf<String>()
        for (path in missing) {
            val bundle = File(rootfs, path)
            bundle.delete()
            bundle.parentFile?.mkdirs()
            try {
                safeWriteText(bundle, certs.toString())
                bundle.setReadable(true, false)
                written.add(path)
            } catch (e: Exception) {
                Log.w("DistroInstaller", "Failed to write CA bundle to $path: ${e.message}")
            }
        }
        return if (written.isNotEmpty()) "Populated CA certificates: ${written.joinToString()}" else null
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

    private suspend fun setupRootfs(rootfs: File) {
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
        ensureCaCertificates(rootfs)
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
        if (!resolv.exists() || resolv.length() == 0L) {
            writeResolvConf(rootfs)
            repairs.add("Created etc/resolv.conf")
        }

        ensureCaCertificates(rootfs)?.let { repairs.add(it) }

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

    suspend fun uninstall(distroName: String) {
        val validName = validateDistroName(distroName)
        val dir = getRootfsDir(validName)
        deleteRootfsSafe(dir)
        com.redt.util.Format.invalidate(dir)
        File(context.filesDir, "installed/$validName").delete()
        val distro = DistroRegistry.allDistros.firstOrNull { it.name == validName }
        val cached = if (distro != null) cachedTarballFile(distro)
        else File(tarballDir(), "$validName.tar.xz")
        cached.delete()
        notifyDocumentRootsChanged()
    }

    fun backup(distroName: String, outDir: File): Boolean {
        val rootfsDir = getRootfsDir(distroName)
        val backupFile = File(outDir, "${distroName}_backup.tar.gz")
        var proc: Process? = null
        return try {
            val pb = ProcessBuilder(
                "tar", "-czf", backupFile.absolutePath,
                "-C", rootfsDir.parentFile?.absolutePath ?: "", rootfsDir.name
            )
            pb.redirectErrorStream(true)
            proc = pb.start()
            // Drain merged stdout/stderr on a separate thread to avoid the pipe
            // buffer filling and blocking the process while we wait.
            val drain = Thread { try { proc.inputStream.use { it.readBytes() } } catch (_: Exception) {} }
            drain.isDaemon = true
            drain.start()
            val finished = proc.waitFor(BACKUP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                killProcess(proc!!)
                Log.w("DistroInstaller", "System tar timed out, falling back to Java backup")
                backupWithJava(rootfsDir, backupFile)
            } else {
                drain.join(2000)
                if (proc.exitValue() == 0 && backupFile.exists() && backupFile.length() > 0) {
                    true
                } else {
                    Log.w("DistroInstaller", "System tar failed, falling back to Java backup")
                    backupWithJava(rootfsDir, backupFile)
                }
            }
        } catch (_: Exception) {
            try {
                backupWithJava(rootfsDir, backupFile)
            } catch (e: Exception) {
                Log.e("DistroInstaller", "Java backup fallback failed", e)
                false
            }
        } finally {
            proc?.destroy()
        }
    }

    private fun backupWithJava(rootfsDir: File, backupFile: File): Boolean {
        if (backupFile.exists()) backupFile.delete()
        var ok = false
        FileOutputStream(backupFile).use { fos ->
            GzipCompressorOutputStream(fos).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    addDirToTar(tar, rootfsDir, rootfsDir.name)
                }
            }
            ok = true
        }
        return ok && backupFile.exists() && backupFile.length() > 0
    }

    private fun addDirToTar(tar: TarArchiveOutputStream, dir: File, entryName: String) {
        val isLink = try {
            OsConstants.S_ISLNK(Os.lstat(dir.absolutePath).st_mode)
        } catch (_: Exception) {
            false
        }
        if (isLink) {
            val entry = TarArchiveEntry(entryName)
            try {
                val target = try { Os.readlink(dir.absolutePath) } catch (_: Exception) { "" }
                if (target.isNotEmpty()) {
                    entry.setLinkName(target)
                }
                tar.putArchiveEntry(entry)
                tar.closeArchiveEntry()
            } catch (_: Exception) {}
            return
        }
        if (dir.isFile) {
            val entry = try { TarArchiveEntry(dir) } catch (_: Exception) { TarArchiveEntry(entryName) }
            entry.name = entryName
            tar.putArchiveEntry(entry)
            FileInputStream(dir).use { it.copyTo(tar) }
            tar.closeArchiveEntry()
            return
        }
        val dirEntry = TarArchiveEntry(dir, entryName)
        tar.putArchiveEntry(dirEntry)
        tar.closeArchiveEntry()
        dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
            addDirToTar(tar, child, "$entryName/${child.name}")
        }
    }

    fun tarballCacheSize(): Long =
        com.redt.util.Format.dirSize(tarballDir())

    fun clearTarballCache(): Boolean {
        var ok = true
        tarballDir().listFiles()?.forEach {
            if (it.isFile && !it.delete()) ok = false
        }
        return ok
    }

    private companion object {
        const val BACKUP_TIMEOUT_SECONDS = 600L
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
