package alpiner.app.distro

import android.content.Context
import android.os.StatFs
import android.util.Log
import alpiner.app.storage.DocumentsProvider
import alpiner.app.ui.Prefs
import alpiner.app.util.FileUtil
import alpiner.app.util.Format
import alpiner.app.util.isUnder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class AlpineInstaller(private val context: Context) {

    data class Progress(val percent: Int, val speed: String)

    /** One repair outcome; [warning] lines are surfaced in the session log. */
    data class RepairLine(val message: String, val warning: Boolean = false)

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
     *   draining on an IO thread after a retry already reset the flag.
     */
    private fun beginJob(): Int {
        cancelled = false
        return generation.incrementAndGet()
    }

    /** Scratch file for the in-flight download; never kept after install. */
    private fun downloadTmpFile(): File = File(context.cacheDir, "alpine-download.tmp")

    suspend fun install(
        distro: Distro,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val gen = beginJob()
        try {
            val tmp = downloadTmpFile()
            if (tmp.exists()) tmp.delete()
            Log.i("AlpineInstaller", "Downloading ${distro.tarballUrl}")
            // Download and verify before touching any existing rootfs, so a
            // network failure cannot destroy a working install.
            val sha = downloadTarball(distro.tarballUrl, tmp, onProgress, gen)
            checkCancel(gen)
            if (distro.sha256.isNotEmpty() && sha != distro.sha256.lowercase()) {
                throw Exception("SHA-256 mismatch: expected ${distro.sha256}, got $sha")
            }

            val rootfsDir = getRootfsDir(distro.name)
            if (rootfsDir.exists()) FileUtil.deleteTreeWithoutFollowingLinks(rootfsDir)
            rootfsDir.mkdirs()

            extractTarball(tmp, rootfsDir, onProgress, gen)
            checkCancel(gen)
            saveInstalled(distro.name)
            if (!tmp.delete() && tmp.exists()) {
                Log.w("AlpineInstaller", "Could not delete download temp file")
            }
            Log.i("AlpineInstaller", "Install complete for ${distro.name}")
        } catch (e: CancelledException) {
            Log.i("AlpineInstaller", "Install cancelled for ${distro.name}")
            cleanup(distro, gen)
            throw e
        } catch (e: CancellationException) {
            cleanup(distro, gen)
            throw e
        } catch (e: Exception) {
            Log.e("AlpineInstaller", "Install failed", e)
            cleanup(distro, gen)
            throw e
        }
    }

    class CancelledException : Exception("Installation cancelled")

    private fun checkCancel(gen: Int) {
        if (cancelled || generation.get() != gen) throw CancelledException()
    }

    private suspend fun cleanup(distro: Distro, gen: Int) {
        // A newer job now owns these paths and handles them itself; deleting
        // here would race its download/extraction.
        if (generation.get() != gen) return
        // Runs even when this coroutine is being cancelled: a cancelled
        // install must not leave a half-extracted rootfs behind. Each step is
        // best-effort so one failure cannot block the remaining cleanup.
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { FileUtil.deleteTreeWithoutFollowingLinks(getRootfsDir(distro.name)) }
            runCatching { downloadTmpFile().delete() }
            runCatching { installedMarker(distro.name).delete() }
            DocumentsProvider.notifyRootsChanged(context)
        }
    }

    /** Downloads [urlString] to [dest], returning the file's SHA-256 hex digest. */
    private fun downloadTarball(
        urlString: String,
        dest: File,
        onProgress: (Progress) -> Unit,
        gen: Int
    ): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = Prefs.CONNECT_TIMEOUT_MS
            conn.readTimeout = Prefs.READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.connect()

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${conn.responseCode} for $urlString")
            }
            val total = conn.contentLengthLong
            if (total > 0) {
                // Incoming bytes plus room for the extracted rootfs
                // (gz decompresses roughly ROOTFS_EXPANSION_FACTOR:1).
                requireFreeSpace(total + total * ROOTFS_EXPANSION_FACTOR, "installing ${dest.name}")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val throttler = ProgressThrottler(PROGRESS_EMIT_INTERVAL_MS)
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            FileOutputStream(dest).use { output ->
                conn.inputStream.use { input ->
                    var downloaded = 0L
                    val startTime = System.currentTimeMillis()
                    readBlocks(input, buffer) { read ->
                        checkCancel(gen)
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        val finished = total > 0 && downloaded >= total
                        if (!throttler.shouldEmit(now, finished)) return@readBlocks
                        val elapsed = (now - startTime) / 1000
                        val speed = if (elapsed > 0) "${(downloaded / 1000 / elapsed)} KB/s" else "0 KB/s"
                        if (total > 0) {
                            onProgress(Progress(((downloaded * 100) / total).toInt(), speed))
                        } else {
                            onProgress(Progress(-1, "${Format.size(downloaded)} - $speed"))
                        }
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun requireFreeSpace(needed: Long, what: String) {
        if (needed <= 0) return
        val stat = StatFs(context.filesDir.absolutePath)
        val available = stat.availableBytes
        if (available < needed) {
            throw Exception(
                "Not enough free space for $what: need " +
                    "${Format.size(needed)}, " +
                    "have ${Format.size(available)}"
            )
        }
    }

    private fun extractTarball(
        tarball: File, dest: File,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) {
        FileInputStream(tarball).use { fis ->
            BufferedInputStream(fis, 65536).use { bis ->
                GzipCompressorInputStream(bis).use { gzIn ->
                    TarArchiveInputStream(gzIn).use { tarIn ->
                        extractTarEntries(tarIn, dest, onProgress, gen)
                    }
                }
            }
        }
    }

    /** Streams [input] through [buffer], invoking [onBlock] with each read's byte count. */
    private inline fun readBlocks(input: InputStream, buffer: ByteArray, onBlock: (Int) -> Unit) {
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            onBlock(read)
        }
    }

    /** Emits at most once per [intervalMs]; forced emissions always pass. */
    private class ProgressThrottler(private val intervalMs: Long) {
        private var lastEmit = 0L

        fun shouldEmit(now: Long, force: Boolean = false): Boolean {
            if (!force && now - lastEmit < intervalMs) return false
            lastEmit = now
            return true
        }
    }

    private fun extractTarEntries(
        tarIn: TarArchiveInputStream, dest: File,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) {
        // 8 MB / 515 entries extracts in seconds: an indeterminate bar is enough.
        onProgress(Progress(-1, "Extracting"))
        val canonicalDest = dest.canonicalFile
        val buf = ByteArray(65536)

        fun processEntry(entry: TarArchiveEntry) {
            // Alpine minirootfs entries start with "./"; strip only that, so a
            // first entry like "bin/..." can never swallow a real path component.
            val entryName = entry.name.removePrefix("./")
            // Root-dir marker entries ("./", ".") carry no content and resolve
            // to the destination itself; skip them instead of rejecting them
            // as "unsafe".
            if (entryName.isEmpty() || entryName == ".") return
            val target = File(dest, entryName).canonicalFile
            if (target == canonicalDest || !target.isUnder(canonicalDest)) {
                throw Exception("Unsafe archive entry: ${entry.name}")
            }
            if (entry.isSymbolicLink) {
                // The link target is interpreted inside the guest by proot, so
                // only the entry's own location needs to stay in the rootfs.
                try {
                    target.parentFile?.mkdirs()
                    target.delete()
                    android.system.Os.symlink(entry.linkName, target.absolutePath)
                } catch (e: Exception) {
                    Log.w("AlpineInstaller", "Symlink failed ${entry.name}: ${e.message}")
                }
            } else if (entry.isLink) {
                // Hard link: payload lives at linkName, extracted earlier in
                // this archive. Falling back to a copy keeps the content even
                // on filesystems where link(2) fails.
                val linkName = entry.linkName.removePrefix("./")
                val source = if (linkName.isEmpty()) null else File(dest, linkName).canonicalFile
                if (source == null || !source.isUnder(canonicalDest) || !source.isFile) {
                    Log.w("AlpineInstaller", "Skipping hard link ${entry.name} -> ${entry.linkName}")
                } else {
                    target.parentFile?.mkdirs()
                    target.delete()
                    try {
                        android.system.Os.link(source.absolutePath, target.absolutePath)
                    } catch (_: Exception) {
                        source.copyTo(target, overwrite = true)
                    }
                }
            } else if (entry.isDirectory) {
                target.mkdirs()
                runCatching { android.system.Os.chmod(target.absolutePath, 0x1ED) } // 0755
            } else {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    readBlocks(tarIn, buf) { read ->
                        checkCancel(gen)
                        out.write(buf, 0, read)
                    }
                }
                // Tar mode bits mapped to read-for-all, write-for-owner,
                // execute when any execute bit is set.
                val perm = if ((entry.mode and 0b001001001) != 0) 0x1ED else 0x1A4 // 0755 : 0644
                runCatching { android.system.Os.chmod(target.absolutePath, perm) }
            }
        }
        var entry: TarArchiveEntry? = tarIn.getNextEntry()
        while (entry != null) {
            checkCancel(gen)
            processEntry(entry)
            entry = tarIn.getNextEntry()
        }
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

    private fun writeResolvConf(resolv: File) {
        resolv.parentFile?.mkdirs()
        // A symlink (e.g. -> /run/resolvconf/resolv.conf) must be removed
        // first so writeText below cannot follow it out of the rootfs.
        if (FileUtil.isSymlink(resolv)) resolv.delete()
        resolv.writeText(guestDnsServers.joinToString("") { "nameserver $it\n" })
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
        group.writeText(sb.toString())
    }

    /** Supplementary group IDs of this process, from /proc/self/status. */
    private fun selfGroupIds(): List<Int> = try {
        File("/proc/self/status").readLines()
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

    fun repairRootfs(rootfs: File): List<RepairLine> = buildList {
        addAll(repairPasswdEntry(rootfs))
        ensureSupplementaryGroups(rootfs)
        addAll(repairHosts(rootfs))
        val rootDir = File(rootfs, "root")
        if (!rootDir.exists() && rootDir.mkdirs()) add(RepairLine("Created /root"))
        addAll(repairShell(rootfs))
        val resolv = File(rootfs, "etc/resolv.conf")
        if (needsResolvRewrite(resolv)) {
            writeResolvConf(resolv)
            add(RepairLine("Rebuilt etc/resolv.conf (fixed DNS)"))
        }
    }

    private fun repairPasswdEntry(rootfs: File): List<RepairLine> {
        val uid = android.os.Process.myUid()
        val passwd = File(rootfs, "etc/passwd")
        // Match the uid field specifically (name:passwd:uid:gid:...), not any
        // occurrence of the number in a foreign line.
        val hasEntry = passwd.exists() && passwd.readLines().any { line ->
            line.startsWith("root:") && line.split(':').getOrNull(2) == uid.toString()
        }
        if (hasEntry) return emptyList()
        passwd.parentFile?.mkdirs()
        passwd.appendText("root:x:$uid:0:root:/root:/bin/sh\n")
        return listOf(RepairLine("Added passwd entry for uid $uid"))
    }

    private fun repairHosts(rootfs: File): List<RepairLine> {
        val hosts = File(rootfs, "etc/hosts")
        if (hosts.exists() && hosts.readText().contains("127.0.0.1")) return emptyList()
        hosts.parentFile?.mkdirs()
        hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        return listOf(RepairLine("Created /etc/hosts"))
    }

    /** Makes bin/sh usable, preferring a busybox symlink over a copy. */
    private fun repairShell(rootfs: File): List<RepairLine> {
        val repairs = mutableListOf<RepairLine>()
        val busybox = File(rootfs, "bin/busybox")
        if (!busybox.exists()) {
            repairs.add(RepairLine("bin/busybox not found in rootfs", warning = true))
            val binDir = File(rootfs, "bin")
            if (binDir.exists()) {
                repairs.add(
                    RepairLine("bin/ contents: ${binDir.list()?.joinToString(", ") ?: "empty"}")
                )
            } else {
                repairs.add(RepairLine("bin/ directory missing!", warning = true))
            }
            return repairs
        }
        if (!busybox.canExecute()) {
            busybox.setExecutable(true, false)
            repairs.add(RepairLine("Made bin/busybox executable"))
        }
        val sh = File(rootfs, "bin/sh")
        if (sh.exists() && sh.canExecute()) return repairs
        sh.delete()
        try {
            android.system.Os.symlink("busybox", sh.absolutePath)
        } catch (_: Exception) {
            busybox.copyTo(sh, overwrite = true)
            sh.setExecutable(true, false)
            repairs.add(RepairLine("Copied bin/busybox -> bin/sh"))
        }
        repairs.add(
            RepairLine(
                if (sh.canExecute()) "bin/sh is now executable"
                else "bin/sh still not executable",
                warning = !sh.canExecute()
            )
        )
        return repairs
    }

    fun getRootfsDir(distroName: String): File =
        File(DistroPaths.rootfsParent(context), distroName).canonicalFile

    fun saveInstalled(distroName: String) {
        installedMarker(distroName).writeText(distroName)
        DocumentsProvider.notifyRootsChanged(context)
    }

    /** True when [distroName] finished an install: marker present and tree intact. */
    fun isInstalled(distroName: String): Boolean =
        installedMarker(distroName).exists() && getRootfsDir(distroName).exists()

    /** Marker file whose presence records [distroName] as installed. */
    private fun installedMarker(distroName: String): File =
        File(File(context.filesDir, "installed").apply { mkdirs() }, distroName)

    /**
     * Startup cleanup for disk usage not owned by any visible feature:
     * - rootfs dirs without an "installed" marker (husk of an interrupted
     *   uninstall) that are at least a day old, so an in-flight install (which
     *   also has no marker yet) is never touched;
     * - leftovers from older versions (tarball cache) and stale download
     *   temp files older than a week.
     */
    suspend fun sweepOrphanFiles() = withContext(Dispatchers.IO) {
        val dayMs = 24L * 60 * 60 * 1000
        val huskCutoff = System.currentTimeMillis() - dayMs
        val staleCutoff = System.currentTimeMillis() - 7 * dayMs
        val rootfsParent = DistroPaths.rootfsParent(context).canonicalFile
        rootfsParent.listFiles()?.forEach { dir ->
            if (dir.isDirectory && !FileUtil.isSymlink(dir) &&
                !installedMarker(dir.name).exists() &&
                dir.lastModified() < huskCutoff
            ) {
                Log.i("AlpineInstaller", "Sweeping orphan rootfs ${dir.name}")
                FileUtil.deleteTreeWithoutFollowingLinks(dir)
            }
        }
        // Legacy tarball cache from versions that kept the downloaded archive.
        FileUtil.deleteTreeWithoutFollowingLinks(File(context.filesDir, "tarballs"))
        val tmp = downloadTmpFile()
        if (tmp.isFile && tmp.lastModified() < staleCutoff) {
            Log.i("AlpineInstaller", "Sweeping stale download temp file")
            tmp.delete()
        }
    }

    private companion object {
        private const val PROGRESS_EMIT_INTERVAL_MS = 100L
        private const val DOWNLOAD_BUFFER_BYTES = 65536

        /**
         * Room reserved for the extracted rootfs on top of the tarball:
         * gzip-compressed rootfs archives decompress roughly 2:1.
         */
        private const val ROOTFS_EXPANSION_FACTOR = 2L
    }
}
