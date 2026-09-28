package alpiner.app.distro

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.util.Log
import alpiner.app.R
import alpiner.app.proot.ProotInstaller
import alpiner.app.storage.DocumentsProvider
import alpiner.app.util.FileUtil
import alpiner.app.util.Format
import alpiner.app.util.isUnder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads, verifies and extracts the Alpine rootfs. The install runs in
 * [scope] (the application scope) so activity recreation cannot cancel a
 * multi-megabyte download; the UI only observes [state].
 */
class AlpineInstaller(private val context: Context, private val scope: CoroutineScope) {

    /** [percent] < 0 means indeterminate; [detail] is speed or phase text. */
    data class Progress(val percent: Int, val detail: String)

    sealed interface State {
        data object Idle : State
        data class Running(val progress: Progress) : State
        data class Failed(val message: String) : State
        /** Finished successfully; the UI calls [acknowledgeDone] once handled. */
        data object Done : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var installJob: Job? = null

    /** Canonical rootfs path, as passed to proot and used for extraction. */
    val rootfsDir: File get() = Alpine.rootfsDir(context).canonicalFile

    /** True when an install finished: marker present and tree intact. */
    val isInstalled: Boolean get() = installedMarker().exists() && rootfsDir.exists()

    /** Starts an install unless one is already running. Main thread only. */
    fun install() {
        val previous = installJob
        if (previous?.isActive == true) return
        installJob = scope.launch {
            // A cancelled install may still be cleaning up its files; wait so
            // its cleanup cannot race this install's download/extraction.
            previous?.join()
            _state.value = State.Running(Progress(-1, "Preparing..."))
            _state.value = try {
                withContext(Dispatchers.IO) { runInstall() }
                State.Done
            } catch (e: CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Install failed", e)
                State.Failed(e.message ?: "Unknown error")
            }
        }
    }

    /** Aborts a running install; its partial files are removed. */
    fun cancel() {
        installJob?.cancel()
    }

    fun acknowledgeDone() {
        if (_state.value == State.Done) _state.value = State.Idle
    }

    private fun report(progress: Progress) {
        _state.value = State.Running(progress)
    }

    private suspend fun runInstall() {
        check(ProotInstaller.isInstalled(context)) { context.getString(R.string.proot_extraction_failed) }
        val job = currentCoroutineContext().job
        try {
            val tmp = downloadTmpFile()
            if (tmp.exists()) tmp.delete()
            Log.i(TAG, "Downloading ${Alpine.TARBALL_URL}")
            // Download and verify before touching any existing rootfs, so a
            // network failure cannot destroy a working install.
            val sha = downloadTarball(Alpine.TARBALL_URL, tmp, job)
            if (sha != Alpine.SHA256.lowercase()) {
                throw Exception("SHA-256 mismatch: expected ${Alpine.SHA256}, got $sha")
            }

            val rootfs = rootfsDir
            if (rootfs.exists()) FileUtil.deleteTreeWithoutFollowingLinks(rootfs)
            rootfs.mkdirs()
            extractTarball(tmp, rootfs, job)
            job.ensureActive()

            installedMarker().writeText(Alpine.NAME)
            DocumentsProvider.notifyRootsChanged(context)
            if (!tmp.delete() && tmp.exists()) Log.w(TAG, "Could not delete download temp file")
            Log.i(TAG, "Install complete")
        } catch (e: Throwable) {
            // Runs even while being cancelled: an aborted install must not
            // leave a half-extracted rootfs behind. Each step is best-effort
            // so one failure cannot block the rest.
            withContext(NonCancellable) {
                runCatching { FileUtil.deleteTreeWithoutFollowingLinks(rootfsDir) }
                runCatching { downloadTmpFile().delete() }
                runCatching { installedMarker().delete() }
                DocumentsProvider.notifyRootsChanged(context)
            }
            throw e
        }
    }

    /** Downloads [url] to [dest], returning the file's SHA-256 hex digest. */
    private fun downloadTarball(url: String, dest: File, job: Job): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${conn.responseCode} for $url")
            }
            val total = conn.contentLengthLong
            if (total > 0) {
                // Incoming bytes plus room for the extracted rootfs.
                requireFreeSpace(total + total * ROOTFS_EXPANSION_FACTOR)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            var downloaded = 0L
            val startTime = System.currentTimeMillis()
            var lastEmit = 0L
            FileOutputStream(dest).use { output ->
                conn.inputStream.use { input ->
                    readBlocks(input, buffer) { read ->
                        job.ensureActive()
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        val finished = total > 0 && downloaded >= total
                        if (!finished && now - lastEmit < PROGRESS_INTERVAL_MS) return@readBlocks
                        lastEmit = now
                        val elapsedSec = (now - startTime) / 1000
                        val speed = if (elapsedSec > 0) "${downloaded / 1000 / elapsedSec} KB/s" else "0 KB/s"
                        report(
                            if (total > 0) Progress(((downloaded * 100) / total).toInt(), speed)
                            else Progress(-1, "${Format.size(downloaded)} - $speed")
                        )
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun requireFreeSpace(needed: Long) {
        val available = StatFs(context.filesDir.absolutePath).availableBytes
        if (available < needed) {
            throw Exception(
                "Not enough free space: need ${Format.size(needed)}, have ${Format.size(available)}"
            )
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

    private fun extractTarball(tarball: File, dest: File, job: Job) {
        // 8 MB / 515 entries extracts in seconds: an indeterminate bar is enough.
        report(Progress(-1, "Extracting"))
        val canonicalDest = dest.canonicalFile
        val buffer = ByteArray(BUFFER_BYTES)
        tarball.inputStream().buffered(BUFFER_BYTES).use { fileIn ->
            TarArchiveInputStream(GzipCompressorInputStream(fileIn)).use { tarIn ->
                while (true) {
                    val entry = tarIn.nextEntry ?: break
                    job.ensureActive()
                    extractEntry(tarIn, entry, dest, canonicalDest, buffer, job)
                }
            }
        }
    }

    private fun extractEntry(
        tarIn: TarArchiveInputStream,
        entry: TarArchiveEntry,
        dest: File,
        canonicalDest: File,
        buffer: ByteArray,
        job: Job,
    ) {
        // Alpine minirootfs entries start with "./"; strip only that, so a
        // first entry like "bin/..." can never swallow a real path component.
        val entryName = entry.name.removePrefix("./")
        // Root-dir marker entries ("./", ".") carry no content.
        if (entryName.isEmpty() || entryName == ".") return
        val target = File(dest, entryName).canonicalFile
        if (target == canonicalDest || !target.isUnder(canonicalDest)) {
            throw Exception("Unsafe archive entry: ${entry.name}")
        }
        when {
            entry.isSymbolicLink -> {
                // The link target is interpreted inside the guest by proot, so
                // only the entry's own location needs to stay in the rootfs.
                try {
                    target.parentFile?.mkdirs()
                    target.delete()
                    Os.symlink(entry.linkName, target.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "Symlink failed ${entry.name}: ${e.message}")
                }
            }
            entry.isLink -> {
                // Hard link: payload lives at linkName, extracted earlier in
                // this archive. Falling back to a copy keeps the content even
                // on filesystems where link(2) fails.
                val linkName = entry.linkName.removePrefix("./")
                val source = if (linkName.isEmpty()) null else File(dest, linkName).canonicalFile
                if (source == null || !source.isUnder(canonicalDest) || !source.isFile) {
                    Log.w(TAG, "Skipping hard link ${entry.name} -> ${entry.linkName}")
                    return
                }
                target.parentFile?.mkdirs()
                target.delete()
                try {
                    Os.link(source.absolutePath, target.absolutePath)
                } catch (_: Exception) {
                    source.copyTo(target, overwrite = true)
                }
            }
            entry.isDirectory -> {
                target.mkdirs()
                runCatching { Os.chmod(target.absolutePath, MODE_0755) }
            }
            else -> {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    readBlocks(tarIn, buffer) { read ->
                        job.ensureActive()
                        out.write(buffer, 0, read)
                    }
                }
                // Read-for-all, write-for-owner, execute when any execute bit is set.
                val mode = if ((entry.mode and 0b001001001) != 0) MODE_0755 else MODE_0644
                runCatching { Os.chmod(target.absolutePath, mode) }
            }
        }
    }

    /** Scratch file for the in-flight download; never kept after install. */
    private fun downloadTmpFile(): File = File(context.cacheDir, "alpine-download.tmp")

    /** Marker file whose presence records Alpine as installed. */
    private fun installedMarker(name: String = Alpine.NAME): File =
        File(File(context.filesDir, "installed").apply { mkdirs() }, name)

    /**
     * Startup cleanup for disk usage not owned by any visible feature:
     * - rootfs dirs without an "installed" marker (husk of an interrupted
     *   install) that are at least a day old, so an in-flight install (which
     *   has no marker yet either) is never touched;
     * - download temp files older than a week.
     */
    fun sweepOrphanFiles() = scope.launch(Dispatchers.IO) {
        val dayMs = 24L * 60 * 60 * 1000
        val huskCutoff = System.currentTimeMillis() - dayMs
        val staleCutoff = System.currentTimeMillis() - 7 * dayMs
        Alpine.rootfsParent(context).canonicalFile.listFiles()?.forEach { dir ->
            if (dir.isDirectory && !FileUtil.isSymlink(dir) &&
                !installedMarker(dir.name).exists() &&
                dir.lastModified() < huskCutoff
            ) {
                Log.i(TAG, "Sweeping orphan rootfs ${dir.name}")
                FileUtil.deleteTreeWithoutFollowingLinks(dir)
            }
        }
        val tmp = downloadTmpFile()
        if (tmp.isFile && tmp.lastModified() < staleCutoff) {
            Log.i(TAG, "Sweeping stale download temp file")
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "AlpineInstaller"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
        const val PROGRESS_INTERVAL_MS = 100L
        const val BUFFER_BYTES = 65536
        const val MODE_0755 = 0x1ED
        const val MODE_0644 = 0x1A4

        /** gzip-compressed rootfs archives decompress roughly 2:1. */
        const val ROOTFS_EXPANSION_FACTOR = 2L
    }
}
