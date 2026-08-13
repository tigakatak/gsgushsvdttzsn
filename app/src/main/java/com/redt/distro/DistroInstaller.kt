package com.redt.distro

import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import com.redt.ui.Prefs
import kotlinx.coroutines.Dispatchers
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
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()

            val tarball = File(tarballDir(), "${distro.name}.tar.xz")
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
                rootfsDir.deleteRecursively()
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
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()

            val tarball = File(tarballDir(), "$distroName.tar.xz")
            if (!tarball.exists()) {
                install(distro, onProgress)
                return@withContext true
            }
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

    private fun checkCancel() {
        if (cancelled) throw CancelledException()
    }

    private fun cleanup(distroName: String) {
        if (runCatching { validateDistroName(distroName) }.isFailure) return
        try {
            val dir = getRootfsDir(distroName)
            dir.deleteRecursively()
            com.redt.util.Format.invalidate(dir)
        } catch (_: Exception) {}
        try {
            File(tarballDir(), "$distroName.tar.xz").delete()
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
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            val elapsed = (System.currentTimeMillis() - startTime) / 1000
                            val speed = if (elapsed > 0) {
                                "${(downloaded / 1000 / elapsed)} KB/s"
                            } else "0 KB/s"
                            onProgress(Progress(percent, speed))
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
            process.inputStream.use { input ->
                val counter = CountingInputStream(input)
                BufferedInputStream(counter, 65536).use { bis ->
                    TarArchiveInputStream(bis).use { tarIn ->
                        // Native xz reads the compressed file itself; the only byte
                        // stream we can observe here is the *decompressed* output, so
                        // fall back to a rough 3:1 ratio (xz typically achieves 3-5:1).
                        val approxDecompressedTotal = tarball.length() * 3L
                        extractTarEntries(tarIn, dest, approxDecompressedTotal, onProgress) { counter.bytesRead }
                    }
                }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw Exception("Native xz decompressor failed (exit $exitCode), falling back")
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
    ) {
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
        fun processEntry(entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry) {
            var entryName = entry.name
            if (prefixToStrip.isNotEmpty() && entryName.startsWith(prefixToStrip)) {
                entryName = entryName.removePrefix(prefixToStrip)
            }
            if (entryName.isEmpty()) return
            val canonicalDest = dest.canonicalFile
            val target = File(dest, entryName).canonicalFile
            val isInsideDestination = target != canonicalDest &&
                target.path.startsWith(canonicalDest.path + File.separator)
            if (!isInsideDestination) {
                throw Exception("Unsafe archive entry: ${entry.name}")
            }
            if (entry.isSymbolicLink) {
                val linkTarget = entry.linkName
                try {
                    val canonicalTarget = target.parentFile?.canonicalFile
                    val resolvedTarget = canonicalTarget?.resolve(linkTarget)?.canonicalFile
                    val isSafe = resolvedTarget != null &&
                        (resolvedTarget == canonicalDest ||
                         resolvedTarget.path.startsWith(canonicalDest.path + File.separator))
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
        if (firstEntry != null) processEntry(firstEntry)
        var entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry? = tarIn.getNextEntry()
        while (entry != null) {
            checkCancel()
            processEntry(entry)
            val pct = if (totalForProgress > 0) {
                ((bytesReadProvider() * 100L) / totalForProgress).toInt().coerceIn(0, 99)
            } else 0
            onProgress(Progress(pct, "Extracting"))
            entry = tarIn.getNextEntry()
        }
    }

    private fun writeResolvConf(rootfs: File) {
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        safeWriteText(resolv, "nameserver 8.8.8.8\n")
    }

    private fun ensureSupplementaryGroups(rootfs: File) {
        val group = File(rootfs, "etc/group")
        group.parentFile?.mkdirs()
        val existing = if (group.exists()) group.readText() else ""
        val sb = StringBuilder(existing)
        val baseEntries = listOf(
            "root:x:0:root", "wheel:x:0:root",
            "inet:x:3003:", "everybody:x:9997:"
        )
        for (entry in baseEntries) {
            val name = entry.substringBefore(':')
            if (!existing.contains(":$name")) {
                if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
                sb.append(entry).append('\n')
            }
        }
        try {
            val status = java.io.File("/proc/self/status").readLines()
            val groupsLine = status.firstOrNull { it.startsWith("Groups:") } ?: return
            val gids = groupsLine.removePrefix("Groups:").trim().split("\\s+".toRegex())
            for (gidStr in gids) {
                val gid = gidStr.toIntOrNull() ?: continue
                if (gid <= 0) continue
                val name = "android_$gid"
                if (!existing.contains(":$name:")) {
                    if (!sb.endsWith('\n')) sb.append('\n')
                    sb.append("$name:x:$gid:\n")
                }
            }
        } catch (_: Exception) {}
        safeWriteText(group, sb.toString())
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

    private fun setupRootfs(rootfs: File) {
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

        val subdirs = rootfs.listFiles()?.filter { it.isDirectory && it.name.contains('-') } ?: emptyList()
        for (subdir in subdirs) {
            val innerBin = File(subdir, "bin")
            if (innerBin.exists()) {
                repairs.add("Found nested rootfs in ${subdir.name}/, migrating...")
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
                    subdir.deleteRecursively()
                    repairs.add("Migrated files from ${subdir.name}/ to rootfs")
                } else {
                    repairs.add("WARN: Failed to fully migrate ${subdir.name}/")
                }
            }
        }

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
        if (!resolv.exists()) {
            writeResolvConf(rootfs)
            repairs.add("Created etc/resolv.conf")
        } else if (!resolv.readText().contains("8.8.8.8")) {
            writeResolvConf(rootfs)
            repairs.add("Updated etc/resolv.conf to static DNS")
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

    fun uninstall(distroName: String) {
        val dir = getRootfsDir(distroName)
        dir.deleteRecursively()
        com.redt.util.Format.invalidate(dir)
        File(context.filesDir, "installed/$distroName").delete()
        File(tarballDir(), "$distroName.tar.xz").delete()
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
                return false
            }
            drain.join(2000)
            proc.exitValue() == 0 && backupFile.exists() && backupFile.length() > 0
        } catch (_: Exception) {
            false
        } finally {
            proc?.destroy()
        }
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
