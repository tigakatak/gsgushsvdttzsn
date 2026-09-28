package alpiner.app.service

import android.content.Context
import android.util.Log
import alpiner.app.distro.Alpine
import alpiner.app.distro.RootfsSetup
import alpiner.app.prefs
import alpiner.app.proot.ProotInstaller
import java.io.File
import java.util.TimeZone

/** Prepares the rootfs and the launch script for a new terminal session. */
internal class SessionLauncher(context: Context) {

    data class LaunchSpec(
        val executable: String,
        val workingDirectory: String,
        val arguments: Array<String>,
        val scrollbackRows: Int,
    )

    private val context = context.applicationContext

    /** Single shared launch script; rewritten atomically on every session start. */
    private val launchScript = File(this.context.filesDir, "launch.sh")

    /** Blocking file IO: call off the main thread. */
    fun prepare(): LaunchSpec {
        val rootfsDir = Alpine.rootfsDir(context).canonicalFile
        require(rootfsDir.exists()) { "Alpine rootfs not installed" }

        RootfsSetup.prepare(rootfsDir)
        writeShellConfigs(rootfsDir)
        writeLaunchScript(rootfsDir)

        return LaunchSpec(
            executable = "/system/bin/sh",
            workingDirectory = context.filesDir.absolutePath,
            arguments = arrayOf("-c", launchScript.absolutePath),
            scrollbackRows = context.prefs.scrollbackRows,
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
export UV_THREADPOOL_SIZE=$UV_THREADPOOL_SIZE
export PROOT_LOADER="$prootLoader"
export PROOT_TMP_DIR="$rootfsPath/tmp"
ulimit -n $ULIMIT_NOFILE 2>/dev/null
ulimit -u $ULIMIT_NPROC 2>/dev/null
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

    /**
     * Extra host --bind args mirroring proot-distro's system_bindings():
     * Android vendor/product partitions and linker/property context files,
     * bound only when present and readable on the device.
     */
    private fun optionalHostBinds(): String =
        listOf(
            "/vendor", "/odm", "/product", "/system_ext",
            "/linkerconfig/com.android.art/ld.config.txt",
            "/plat_property_contexts", "/property_contexts",
        )
            .filter { val f = File(it); f.exists() && f.canRead() }
            .joinToString(" ") { "-b $it" }

    private fun writeShellConfigs(rootfsDir: File) {
        try {
            val rootDir = File(rootfsDir, "root").apply { mkdirs() }
            writeBashrc(File(rootDir, ".bashrc"))
            writeIfChanged(File(rootDir, ".startup"), STARTUP_SCRIPT)
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
    private fun writeBashrc(bashrc: File) {
        val existing = runCatching { bashrc.readText() }.getOrDefault("")
        val begin = existing.indexOf(BASHRC_BEGIN)
        val end = if (begin >= 0) existing.indexOf(BASHRC_END, begin) else -1
        val merged = when {
            begin >= 0 && end >= 0 ->
                existing.substring(0, begin) + BASHRC_BLOCK + existing.substring(end + BASHRC_END.length)
            existing.isBlank() -> BASHRC_BLOCK + "\n"
            existing.endsWith("\n") -> existing + BASHRC_BLOCK + "\n"
            else -> existing + "\n\n" + BASHRC_BLOCK + "\n"
        }
        if (merged != existing) bashrc.writeText(merged)
    }

    private fun writeIfChanged(file: File, content: String) {
        if (runCatching { file.readText() }.getOrNull() != content) file.writeText(content)
    }

    private companion object {
        const val UV_THREADPOOL_SIZE = 16
        const val ULIMIT_NOFILE = 65536
        const val ULIMIT_NPROC = 65536

        const val BASHRC_BEGIN = "# >>> alpiner >>>"
        const val BASHRC_END = "# <<< alpiner <<<"

        const val BASHRC_BLOCK = """$BASHRC_BEGIN
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

        const val STARTUP_SCRIPT = """has_bash() { command -v bash >/dev/null 2>&1; }
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
    }
}
