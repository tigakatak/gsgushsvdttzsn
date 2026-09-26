package alpiner.app.service

import android.content.Context
import android.util.Log
import alpiner.app.distro.AlpineInstaller
import alpiner.app.distro.AlpineRegistry
import alpiner.app.proot.ProotInstaller
import alpiner.app.ui.Prefs
import alpiner.app.ui.prefs
import java.io.File
import java.util.TimeZone

internal class SessionLauncher(context: Context) {

    data class LaunchSpec(
        val executable: String,
        val workingDirectory: String,
        val arguments: Array<String>,
        val scrollbackRows: Int,
    )

    private val context = context.applicationContext
    private val installer = AlpineInstaller(this.context)

    /** Single shared launch script; rewritten atomically on every session start. */
    private val launchScript = File(context.filesDir, "launch.sh")

    fun prepare(): LaunchSpec {
        val rootfsDir = installer.getRootfsDir(AlpineRegistry.alpine.name)
        require(rootfsDir.exists()) { "Alpine rootfs not installed" }

        val repairs = installer.repairRootfs(rootfsDir)
        if (repairs.any { it.warning }) {
            Log.w("SessionLauncher", "Rootfs issues:\n${repairs.joinToString("\n") { it.message }}")
        }

        prepareRuntimeDirectories(rootfsDir)
        writeShellConfigs(rootfsDir)
        writeLaunchScript(rootfsDir)

        val prefs = context.prefs()
        val scrollbackIndex = prefs.getInt(Prefs.KEY_SCROLLBACK, Prefs.SCROLLBACK_DEFAULT)
            .coerceIn(Prefs.SCROLLBACK_ROWS.indices)
        return LaunchSpec(
            executable = "/system/bin/sh",
            workingDirectory = context.filesDir.absolutePath,
            arguments = arrayOf("-c", launchScript.absolutePath),
            scrollbackRows = Prefs.SCROLLBACK_ROWS[scrollbackIndex],
        )
    }

    private fun writeLaunchScript(rootfsDir: File) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val prootBin = ProotInstaller.getProotPath(context) ?: "$nativeLibDir/libproot.so"
        val prootLoader = "$nativeLibDir/libloader.so"
        val rootfsPath = rootfsDir.absolutePath
        val timezone = TimeZone.getDefault().id
        val extraBinds = optionalHostBinds()
        val script = """#!/system/bin/sh
export HOME=/root
export TERM=xterm-256color
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export TZ="$timezone"
export TMPDIR=/tmp
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin
export ENV=/root/.startup
export UV_THREADPOOL_SIZE=${Prefs.UV_THREADPOOL_SIZE}
export PROOT_LOADER="$prootLoader"
export PROOT_TMP_DIR="$rootfsPath/tmp"
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
"""
        // Atomic replace: a running session's shell may still hold the old
        // script open while a new session rewrites it.
        val tmp = File(context.filesDir, "launch.sh.tmp")
        try {
            tmp.writeText(script)
            if (!tmp.renameTo(launchScript)) {
                launchScript.writeText(script)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        launchScript.setExecutable(true, false)
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
     * reads .bashrc anyway. Alpiner's customizations therefore live directly in
     * .bashrc inside a marked block that is refreshed in place on upgrade.
     * Anything outside the block (user edits) is preserved.
     */
    private fun writeBashrc(rootDir: File) {
        val bashrc = File(rootDir, ".bashrc")
        val existing = runCatching { bashrc.readText() }.getOrDefault("")
        val block = managedBashrcBlock()
        val begin = existing.indexOf(BASHRC_BEGIN)
        val end = if (begin >= 0) existing.indexOf(BASHRC_END, begin) else -1
        val merged = if (begin >= 0 && end >= 0) {
            existing.substring(0, begin) + block + existing.substring(end + BASHRC_END.length)
        } else {
            appendBashrcBlock(existing, block)
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
    # ENV points ash at this script; it must not leak into the bash session,
    # or a later "sh"/"ash" would re-run it and exec bash again.
    unset ENV
    exec bash -i
fi
"""
        val current = runCatching { startup.readText() }.getOrDefault("")
        if (current != startupScript) {
            startup.writeText(startupScript)
        }
    }

    /** Removes per-session scripts left behind by older Alpiner versions. */
    fun deleteLegacyLaunchScripts() {
        context.filesDir.listFiles { _, name ->
            name.startsWith("launch_") && name.endsWith(".sh")
        }?.forEach { it.delete() }
    }

    private fun managedBashrcBlock(): String = """$BASHRC_BEGIN
export TERM=xterm-256color
stty erase ^?
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
shopt -s checkwinsize histappend
HISTSIZE=1000
HISTFILESIZE=2000
PS1='\[\e[1;32m\]\u@Alpiner\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\$ '
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
        const val BASHRC_BEGIN = "# >>> alpiner >>>"
        const val BASHRC_END = "# <<< alpiner <<<"
    }
}
