package com.redt.service

import android.content.Context
import android.util.Log
import com.redt.distro.DistroInstaller
import com.redt.distro.DistroRegistry
import com.redt.proot.ProotInstaller
import com.redt.ui.Prefs
import com.redt.ui.prefs
import java.io.File
import java.util.TimeZone
import java.util.UUID

internal class SessionLauncher(context: Context) {

    data class LaunchSpec(
        val executable: String,
        val workingDirectory: String,
        val arguments: Array<String>,
        val scrollbackRows: Int,
        val launchScript: File,
    )

    private val context = context.applicationContext
    private val installer = DistroInstaller(this.context)

    fun prepare(): LaunchSpec {
        val rootfsDir = installer.getRootfsDir(DistroRegistry.alpine.name)
        require(rootfsDir.exists()) { "Alpine rootfs not installed" }

        val repairLog = installer.repairRootfs(rootfsDir)
        if (repairLog.contains("WARN") || repairLog.contains("missing")) {
            Log.w("SessionLauncher", "Rootfs issues:\n$repairLog")
        }

        prepareRuntimeDirectories(rootfsDir)
        writeShellConfigs(rootfsDir)

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val prootBin = ProotInstaller.getProotPath(context) ?: "$nativeLibDir/libproot.so"
        // The proot asset build (tools/build-proot-assets.sh) produces only
        // the 64-bit loader: this app is arm64-only and never runs 32-bit
        // guests, so no PROOT_LOADER_32 is exported.
        val prootLoader = "$nativeLibDir/libloader.so"
        val rootfsPath = rootfsDir.absolutePath
        val sessionId = UUID.randomUUID().toString().replace("-", "").take(Prefs.SESSION_ID_LENGTH)
        val launchScript = File(context.filesDir, "launch_$sessionId.sh")
        val timezone = TimeZone.getDefault().id
        val extraBinds = optionalHostBinds()
        launchScript.writeText("""#!/system/bin/sh
export HOME=/root
export TERM=xterm-256color
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export TZ="$timezone"
export TMPDIR=/tmp
export PATH=/system/bin:/system/xbin:/bin:/sbin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin
export ENV=/root/.startup
export UV_THREADPOOL_SIZE=${Prefs.UV_THREADPOOL_SIZE}
export PROOT_LOADER=$prootLoader
export PROOT_TMP_DIR=$rootfsPath/tmp
mkdir -p "$rootfsPath/tmp" "$rootfsPath/run/shm"
ulimit -n ${Prefs.ULIMIT_NOFILE} 2>/dev/null
ulimit -u ${Prefs.ULIMIT_NPROC} 2>/dev/null
exec "$prootBin" -0 -L -r "$rootfsPath" -w /root --link2symlink --sysvipc --ashmem-memfd --kill-on-exit \
    -b /dev -b /proc \
    -b /proc/self/fd:/dev/fd \
    -b /proc/self/fd/0:/dev/stdin \
    -b /proc/self/fd/1:/dev/stdout \
    -b /proc/self/fd/2:/dev/stderr \
    -b "$rootfsPath/tmp:/dev/shm" \
    -b /sys -b /system -b /apex -b /linkerconfig/ld.config.txt \
    -b /sdcard -b /storage -b /mnt \
    -b /dev/urandom:/dev/random \
    $extraBinds \
    /bin/sh -i
""")
        launchScript.setExecutable(true, false)

        val prefs = context.prefs()
        val scrollbackIndex = prefs.getInt(Prefs.KEY_SCROLLBACK, Prefs.SCROLLBACK_DEFAULT)
            .coerceIn(Prefs.SCROLLBACK_ROWS.indices)
        return LaunchSpec(
            executable = "/system/bin/sh",
            workingDirectory = context.filesDir.absolutePath,
            arguments = arrayOf("-c", launchScript.absolutePath),
            scrollbackRows = Prefs.SCROLLBACK_ROWS[scrollbackIndex],
            launchScript = launchScript,
        )
    }

    private fun prepareRuntimeDirectories(rootfsDir: File) {
        val tmp = File(rootfsDir, "tmp").apply { mkdirs() }
        // Shared with the guest via --bind=tmp:/dev/shm; 1777 like proot-distro.
        try {
            android.system.Os.chmod(tmp.absolutePath, 0x3FF) // 01777
        } catch (_: Exception) {}
        File(rootfsDir, "run").mkdirs()
        File(rootfsDir, "run/shm").mkdirs()
        val busybox = File(rootfsDir, "bin/busybox")
        if (busybox.exists() && !busybox.canExecute()) busybox.setExecutable(true, false)
        for (name in listOf("sh", "ash", "bash")) {
            val shell = File(rootfsDir, "bin/$name")
            if (shell.exists() && !shell.canExecute()) shell.setExecutable(true, false)
        }
    }

    /**
     * Extra host --bind args mirroring proot-distro's system_bindings():
     * Android vendor/product partitions and linker/property context files,
     * bound only when present and readable on the device.
     */
    private fun optionalHostBinds(): String {
        val paths = listOf(
            "/vendor", "/odm", "/product", "/system_ext",
            "/linkerconfig/com.android.art/ld.config.txt",
            "/plat_property_contexts", "/property_contexts",
        )
        return paths
            .filter { val f = File(it); f.exists() && f.canRead() }
            .joinToString(" ") { "-b $it" }
    }

    private fun writeShellConfigs(rootfsDir: File) {
        try {
            val rootDir = File(rootfsDir, "root").apply { mkdirs() }
            writeBashrc(rootDir)
            writeStartupScript(rootDir)
        } catch (e: Exception) {
            Log.w("SessionLauncher", "writeShellConfigs failed: ${e.message}")
        }
    }

    /**
     * Alpine's default shell is busybox sh and the minirootfs ships no
     * .bashrc; bash only exists once .startup has installed it, and sh never
     * reads .bashrc anyway. RedT's customizations therefore live directly in
     * .bashrc inside a marked block that is refreshed in place on upgrade.
     * Anything outside the block (user edits) is preserved, and rootfs' from
     * the old indirect scheme (source line + ~/.redt_bashrc) are migrated.
     */
    private fun writeBashrc(rootDir: File) {
        File(rootDir, ".redt_bashrc").delete()

        val bashrc = File(rootDir, ".bashrc")
        val existing = runCatching { bashrc.readText() }.getOrDefault("")
        val withoutLegacy = if (existing.contains(LEGACY_SOURCE_LINE)) {
            existing.lines().filterNot { line ->
                val t = line.trim()
                t == LEGACY_SOURCE_LINE || t == "# RedT additions"
            }.joinToString("\n")
        } else {
            existing
        }

        val block = managedBashrcBlock()
        val begin = withoutLegacy.indexOf(BASHRC_BEGIN)
        val end = if (begin >= 0) withoutLegacy.indexOf(BASHRC_END, begin) else -1
        val merged = if (begin >= 0 && end >= 0) {
            withoutLegacy.substring(0, begin) + block + withoutLegacy.substring(end + BASHRC_END.length)
        } else {
            appendBashrcBlock(withoutLegacy, block)
        }
        if (merged != existing) bashrc.writeText(merged)
    }

    private fun appendBashrcBlock(content: String, block: String): String = when {
        content.isBlank() -> block + "\n"
        content.endsWith("\n") -> content + block + "\n"
        else -> content + "\n\n" + block + "\n"
    }

    private fun writeStartupScript(rootDir: File) {
        val startup = File(rootDir, ".startup")
        val startupScript = """has_bash() { command -v bash >/dev/null 2>&1; }
if [ ! -f /root/.init_done ] || ! has_bash; then
    echo '>>> First-time distro setup...'
    if apk update 2>/root/.setup_error.log && apk add -q bash 2>>/root/.setup_error.log; then
        if has_bash; then
            touch /root/.init_done
            echo '>>> Setup complete.'
        else
            echo '>>> Install reported success but packages are missing - will retry next session.'
            echo '>>> Details: /root/.setup_error.log'
        fi
    else
        echo '>>> Setup was interrupted or failed - starting a repair shell.'
        echo '>>> Details: /root/.setup_error.log'
        echo '>>> Run manually: apk update && apk add bash'
    fi
fi
if command -v bash >/dev/null 2>&1; then
    exec bash -i
fi
"""
        val needsRewrite = !startup.exists() ||
            runCatching { startup.readText() != startupScript }.getOrDefault(true)
        if (needsRewrite) {
            startup.writeText(startupScript)
        }
    }

    private fun managedBashrcBlock(): String = """$BASHRC_BEGIN
export TERM=xterm-256color
stty erase ^?
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
shopt -s checkwinsize histappend
HISTSIZE=1000
HISTFILESIZE=2000
PS1='\[\e[1;32m\]\u@RedT\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\$ '
alias ls='ls --color=auto'
alias ll='ls -lah --color=auto'
alias la='ls -A --color=auto'
alias grep='grep --color=auto'
alias ..='cd ..'
alias rm='rm -i'
alias cp='cp -i'
alias mv='mv -i'
$BASHRC_END"""

    private companion object {
        const val BASHRC_BEGIN = "# >>> redt >>>"
        const val BASHRC_END = "# <<< redt <<<"
        const val LEGACY_SOURCE_LINE = "[ -r ~/.redt_bashrc ] && . ~/.redt_bashrc"
    }
}
