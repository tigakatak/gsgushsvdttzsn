package alpiner.app.distro

import android.system.Os
import android.util.Log
import alpiner.app.util.FileUtil
import java.io.File

/**
 * Idempotent fixes applied to the rootfs before every session start, so a
 * damaged or hand-edited rootfs still boots. Repairs are logged; problems
 * that cannot be fixed here are logged as warnings.
 */
internal object RootfsSetup {
    private const val TAG = "RootfsSetup"

    /**
     * The guest always gets this fixed public DNS pair. Device DNS is never
     * used: it breaks when the network changes and VPN apps (NetGuard,
     * Blokada, ...) can inject resolver addresses (198.18.0.0/15) that only
     * work while their tunnel is up.
     */
    private val GUEST_DNS_SERVERS = listOf("8.8.8.8", "1.1.1.1")

    fun prepare(rootfs: File) {
        repairPasswd(rootfs)
        ensureSupplementaryGroups(rootfs)
        repairHosts(rootfs)
        val rootDir = File(rootfs, "root")
        if (!rootDir.exists() && rootDir.mkdirs()) Log.i(TAG, "Created /root")
        repairShell(rootfs)
        repairResolvConf(File(rootfs, "etc/resolv.conf"))
        prepareRuntimeDirectories(rootfs)
    }

    private fun repairPasswd(rootfs: File) {
        val uid = android.os.Process.myUid()
        val passwd = File(rootfs, "etc/passwd")
        // Match the uid field specifically (name:passwd:uid:gid:...), not any
        // occurrence of the number in a foreign line.
        val hasEntry = passwd.exists() && passwd.readLines().any { line ->
            line.startsWith("root:") && line.split(':').getOrNull(2) == uid.toString()
        }
        if (hasEntry) return
        passwd.parentFile?.mkdirs()
        passwd.appendText("root:x:$uid:0:root:/root:/bin/sh\n")
        Log.i(TAG, "Added passwd entry for uid $uid")
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

        val baseGroups = listOf("root:x:0:root", "wheel:x:0:root", "inet:x:3003:", "everybody:x:9997:")
        val androidGroups = selfGroupIds().map { gid -> "android_$gid:x:$gid:" }
        val additions = (baseGroups + androidGroups).filter { existingNames.add(it.substringBefore(':')) }
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

    private fun repairHosts(rootfs: File) {
        val hosts = File(rootfs, "etc/hosts")
        if (hosts.exists() && hosts.readText().contains("127.0.0.1")) return
        hosts.parentFile?.mkdirs()
        hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        Log.i(TAG, "Created /etc/hosts")
    }

    /** Makes bin/sh usable, preferring a busybox symlink over a copy. */
    private fun repairShell(rootfs: File) {
        val busybox = File(rootfs, "bin/busybox")
        if (!busybox.exists()) {
            val binDir = File(rootfs, "bin")
            val contents = if (binDir.exists()) binDir.list()?.joinToString(", ") ?: "empty" else "missing"
            Log.w(TAG, "bin/busybox not found in rootfs; bin/: $contents")
            return
        }
        if (!busybox.canExecute()) {
            busybox.setExecutable(true, false)
            Log.i(TAG, "Made bin/busybox executable")
        }
        val sh = File(rootfs, "bin/sh")
        if (sh.exists() && sh.canExecute()) return
        sh.delete()
        try {
            Os.symlink("busybox", sh.absolutePath)
        } catch (_: Exception) {
            busybox.copyTo(sh, overwrite = true)
            sh.setExecutable(true, false)
            Log.i(TAG, "Copied bin/busybox -> bin/sh")
        }
        if (sh.canExecute()) Log.i(TAG, "bin/sh is now executable")
        else Log.w(TAG, "bin/sh still not executable")
    }

    /**
     * Rewrites [resolv] when it is missing/unreadable or its nameserver list
     * differs from [GUEST_DNS_SERVERS] (baked device DNS, VPN benchmark
     * ranges, loopback stubs, manual edits).
     */
    private fun repairResolvConf(resolv: File) {
        val nameservers = try {
            resolv.readLines().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("nameserver")) trimmed.removePrefix("nameserver").trim() else null
            }
        } catch (_: Exception) {
            null
        }
        if (nameservers == GUEST_DNS_SERVERS) return
        resolv.parentFile?.mkdirs()
        // A symlink (e.g. -> /run/resolvconf/resolv.conf) must be removed
        // first so writeText below cannot follow it out of the rootfs.
        if (FileUtil.isSymlink(resolv)) resolv.delete()
        resolv.writeText(GUEST_DNS_SERVERS.joinToString("") { "nameserver $it\n" })
        Log.i(TAG, "Rebuilt etc/resolv.conf (fixed DNS)")
    }

    private fun prepareRuntimeDirectories(rootfs: File) {
        val tmp = File(rootfs, "tmp").apply { mkdirs() }
        // Shared with the guest via --bind=tmp:/dev/shm; 1777 like proot-distro.
        runCatching { Os.chmod(tmp.absolutePath, 0x3FF) } // 01777
        File(rootfs, "run/shm").mkdirs()
    }
}
