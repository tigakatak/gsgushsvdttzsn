package com.redtermapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ContextMenu
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.service.TerminalService
import com.redtermapp.util.Format
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalActivity : AppCompatActivity() {

    private lateinit var distroName: String
    private lateinit var terminalView: TerminalView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sessionListContainer: LinearLayout
    private lateinit var rootContainer: LinearLayout
    private lateinit var extraKeysWrapper: LinearLayout
    private lateinit var terminalWrapper: FrameLayout

    private var terminalBackend: TerminalBackend? = null
    private var currentFontSize = 20
    private var extraKeysColumnMode = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val sessionLaunchScripts = mutableMapOf<com.termux.terminal.TerminalSession, String>()

    companion object {
        const val EXTRA_DISTRO = "distro"

        fun launch(context: Context, distroName: String) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java).apply {
                    putExtra(EXTRA_DISTRO, distroName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
    }

    private val sessionModel: TerminalViewModel by lazy { TerminalViewModel.get(application) }
    private val sessions: List<TerminalSession> get() = sessionModel.sessions.value
    private val currentIndex: Int get() = sessionModel.currentIndex.value

    private val nightReceiver = makeNightModeReceiver(this)

    private fun wireBackend(backend: TerminalBackend) {
        backend.onSessionFinished = { finishedSession -> handleSessionFinished(finishedSession) }
        backend.onLinkTap = { link, isPath -> handleLinkTap(link, isPath) }
        backend.onModifierConsumed = { consumeModifiers() }
        backend.onEmulatorReady = { applyEmulatorColors(backend.view) }
    }

    private fun handleLinkTap(link: String, isPath: Boolean) {
        if (isPath) return
        android.app.AlertDialog.Builder(this)
            .setTitle(link)
            .setItems(arrayOf("Open in browser", "Copy link")) { _, which ->
                when (which) {
                    0 -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                                if (link.startsWith("http")) link else "https://$link"
                            )))
                        } catch (_: Exception) {
                            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> copyText(link)
                }
            }
            .show()
    }

    private fun copyText(text: String) {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        clip.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        setupImeVisibilityListener()

        if (!com.redtermapp.util.StoragePermission.isAccessible(this)) {
            Toast.makeText(
                this,
                "RedTerm needs All files access to use /storage/emulated/0 in the terminal",
                Toast.LENGTH_LONG
            ).show()
            com.redtermapp.util.StoragePermission.requestAccess(this)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        distroName = intent?.getStringExtra(EXTRA_DISTRO) ?: "alpine"
        prefs()
            .edit().putString(Prefs.KEY_LAST_DISTRO, distroName).apply()
        terminalView = findViewById(R.id.terminal_view)
        drawerLayout = findViewById(R.id.drawer_layout)
        sessionListContainer = findViewById(R.id.session_list_container)
        rootContainer = findViewById(R.id.root_container)
        extraKeysWrapper = findViewById(R.id.extra_keys_wrapper)
        terminalWrapper = findViewById(R.id.terminal_wrapper)

        registerForContextMenu(terminalView)

        setupExtraKeysRow1()
        setupExtraKeysRow2()

        val prefs = prefs()

        val rootfsDir = DistroInstaller(applicationContext).getRootfsDir(distroName)
        val sizeLabel = findViewById<TextView>(R.id.distro_size_label)
        val cached = Format.cachedSize(rootfsDir)
        sizeLabel.text = if (cached != null) "$distroName (${Format.size(cached)})" else distroName
        Format.dirSizeAsync(rootfsDir) { bytes ->
            sizeLabel.text = "$distroName (${Format.size(bytes)})"
        }

        if (prefs.getBoolean(Prefs.KEY_WAKELOCK, Prefs.WAKELOCK_DEFAULT)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (prefs.getBoolean("autohide_keys", false)) {
            toggleExtraKeys(false)
        }

        findViewById<TextView>(R.id.new_session_button).setOnClickListener {
            createNewSession()
        }

        registerNightModeReceiver(nightReceiver)
        sweepStaleLaunchScripts()
        if (sessions.isEmpty()) {
            createNewSession()
        } else {
            val backend = TerminalBackend(terminalView, this).also {
                terminalBackend = it
                terminalView.setTerminalViewClient(it)
                wireBackend(it)
            }
            for (s in sessions) {
                s.updateTerminalSessionClient(backend)
            }
            currentFontSize = prefs.getInt("font_size", 20)
            terminalView.setTextSize(currentFontSize)
            applyFontFromPrefs(prefs)
            terminalView.setBackgroundColor(themeColor(R.attr.terminalBg, 0xFF1E1E2E.toInt()))
            val safeIdx = currentIndex.coerceIn(0, sessions.lastIndex)
            terminalView.attachSession(sessions[safeIdx])
            terminalView.onScreenUpdated()
            terminalView.post {
                terminalView.requestFocus()
                terminalView.isFocusableInTouchMode = true
            }
            val target = sessions.indexOfFirst { it.mSessionName.equals(distroName, ignoreCase = true) }
            if (target >= 0 && target != currentIndex) {
                sessionModel.switchToSession(target)
                terminalView.attachSession(sessions[target])
                terminalView.onScreenUpdated()
            }
            updateDrawer()
        }
        startForegroundService()
    }

    private fun isRepeatableKey(label: String): Boolean {
        return label in listOf(
            "\u25B2", "UP", "\u25BC", "DOWN", "\u25C0", "LEFT", "\u25B6", "RIGHT",
            "HOME", "END", "DEL", "INS", "\u232B", "BACKSPACE"
        )
    }

    private fun createKeyButton(label: String, action: () -> Unit): Button {
        val textColor = themeColor(R.attr.terminalText, 0xFFCDD6F4.toInt())
        val repeatable = isRepeatableKey(label)
        val isSymbol = label.length == 1 && !label[0].isLetterOrDigit()
        val handler = if (repeatable) android.os.Handler(android.os.Looper.getMainLooper()) else null
        val initialDelay = Prefs.KEY_REPEAT_INITIAL_DELAY
        val repeatDelay = Prefs.KEY_REPEAT_DELAY
        val repeatRunnable = object : Runnable {
            override fun run() {
                action()
                handler?.postDelayed(this, repeatDelay)
            }
        }
        return Button(this).apply {
            text = label
            setTextColor(textColor)
            textSize = if (isSymbol) 16f else 12f
            setBackgroundResource(0)
            isFocusable = false
            isFocusableInTouchMode = false
            setPadding(4, 4, 4, 4)
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { weight = 1f; setMargins(2, 4, 2, 4); gravity = Gravity.CENTER }
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        setBackgroundColor(modifierHighlightColor())
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        if (repeatable && handler != null) {
                            action()
                            handler.postDelayed(repeatRunnable, initialDelay)
                            true
                        } else {
                            false
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        setBackgroundColor(0)
                        handler?.removeCallbacks(repeatRunnable)
                        if (repeatable) true else false
                    }
                    else -> false
                }
            }
            setOnClickListener {
                if (!repeatable) action()
            }
        }
    }

    private fun extraKeyLabels(): Pair<List<String>, List<String>> {
        val prefs = prefs()
        val d1 = "\u2630 ESC \u25B2 \u2014 /"
        val d2 = "TAB \u25C0 \u25BC \u25B6 CTRL"
        val split = { s: String -> s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
        return split(prefs.getString("extra_keys_row1", d1)!!) to
            split(prefs.getString("extra_keys_row2", d2)!!)
    }

    private fun keyAction(label: String): () -> Unit {
        val actions: List<Pair<String, () -> Unit>> = listOf(
            "\u2630" to { toggleSessionsPanel() },
            "MENU" to { toggleSessionsPanel() },
            "ESC" to { session?.writeCodePoint(false, 27); Unit },
            "TAB" to { session?.writeCodePoint(false, 9); Unit },
            "CTRL" to { toggleCtrl() },
            "ALT" to { toggleAlt() },
            "\u25B2" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0); Unit },
            "UP" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0); Unit },
            "\u25BC" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0); Unit },
            "DOWN" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0); Unit },
            "\u25C0" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0); Unit },
            "LEFT" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0); Unit },
            "\u25B6" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0); Unit },
            "RIGHT" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0); Unit },
            "HOME" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_MOVE_HOME, 0); Unit },
            "END" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_MOVE_END, 0); Unit },
            "INS" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_INSERT, 0); Unit },
            "DEL" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_FORWARD_DEL, 0); Unit },
            "\u232B" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DEL, 0); Unit },
            "BACKSPACE" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DEL, 0); Unit },
            "\u2014" to { session?.write("-"); Unit },
        )
        val action = actions.firstOrNull { it.first == label }?.second
            ?: {
                session?.write(label)
                Unit
            }
        if (label == "CTRL" || label == "ALT") return action
        return {
            action()
            consumeModifiers()
        }
    }

    private fun setupExtraKeysRow1() {
        val container = findViewById<LinearLayout>(R.id.extra_keys_container)
        for (label in extraKeyLabels().first) {
            container.addView(createKeyButton(label, keyAction(label)))
        }
    }

    private fun setupExtraKeysRow2() {
        val container = findViewById<LinearLayout>(R.id.extra_keys_container_row2)
        for (label in extraKeyLabels().second) {
            container.addView(createKeyButton(label, keyAction(label)))
        }
    }

    private var ctrlActive = false
    private var altActive = false
    private var lastImeVisible = false

    private fun toggleCtrl() {
        ctrlActive = !ctrlActive
        terminalBackend?.setCtrl(ctrlActive)
        updateModifierButtons()
    }

    private fun toggleAlt() {
        altActive = !altActive
        terminalBackend?.setAlt(altActive)
        updateModifierButtons()
    }

    private fun consumeModifiers() {
        if (!ctrlActive && !altActive) return
        ctrlActive = false
        altActive = false
        terminalBackend?.setCtrl(false)
        terminalBackend?.setAlt(false)
        updateModifierButtons()
    }

    private fun updateModifierButtons() {
        for (rowId in intArrayOf(R.id.extra_keys_container, R.id.extra_keys_container_row2)) {
            val row = findViewById<LinearLayout>(rowId)
            for (i in 0 until row.childCount) {
                val btn = row.getChildAt(i) as? Button ?: continue
                when (btn.text) {
                    "CTRL" -> btn.setBackgroundColor(if (ctrlActive) modifierHighlightColor() else 0)
                    "ALT" -> btn.setBackgroundColor(if (altActive) modifierHighlightColor() else 0)
                }
            }
        }
    }

    private fun setupImeVisibilityListener() {
        val rootView = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (visible != lastImeVisible) {
                lastImeVisible = visible
                updateModifierButtons()
                updateExtraKeysVisibility()
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun toggleSessionsPanel() {
        if (drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
        } else {
            drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
        }
    }

    private fun hideSessionsPanel() {
        drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
    }

    private fun toggleExtraKeys(show: Boolean) {
        extraKeysWrapper.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun updateExtraKeysVisibility() {
        val prefs = prefs()
        toggleExtraKeys(
            if (prefs.getBoolean("autohide_keys", false)) lastImeVisible else true
        )
    }

    private fun switchExtraKeysMode(toColumn: Boolean) {
        if (toColumn == extraKeysColumnMode) return
        extraKeysColumnMode = toColumn

        val row1 = findViewById<LinearLayout>(R.id.extra_keys_container)
        val row2 = findViewById<LinearLayout>(R.id.extra_keys_container_row2)

        if (toColumn) {
            rootContainer.orientation = LinearLayout.HORIZONTAL
            rootContainer.removeView(extraKeysWrapper)
            rootContainer.removeView(terminalWrapper)
            rootContainer.addView(extraKeysWrapper)
            rootContainer.addView(terminalWrapper)

            extraKeysWrapper.orientation = LinearLayout.VERTICAL
            extraKeysWrapper.layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.MATCH_PARENT)

            for (row in listOf(row1, row2)) {
                row.orientation = LinearLayout.VERTICAL
                row.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }

            terminalWrapper.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        } else {
            rootContainer.orientation = LinearLayout.VERTICAL
            rootContainer.removeView(extraKeysWrapper)
            rootContainer.removeView(terminalWrapper)
            rootContainer.addView(terminalWrapper)
            rootContainer.addView(extraKeysWrapper)

            extraKeysWrapper.orientation = LinearLayout.VERTICAL
            extraKeysWrapper.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )

            for (row in listOf(row1, row2)) {
                row.orientation = LinearLayout.HORIZONTAL
                row.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
                )
            }

            terminalWrapper.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        updateButtonLayoutParams(row1)
        updateButtonLayoutParams(row2)
        terminalView.onScreenUpdated()
    }

    private fun updateButtonLayoutParams(container: LinearLayout) {
        for (i in 0 until container.childCount) {
            val btn = container.getChildAt(i) as? Button ?: continue
            btn.layoutParams = if (extraKeysColumnMode) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    setMargins(2, 4, 2, 4)
                    gravity = Gravity.CENTER
                }
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(2, 4, 2, 4)
                    gravity = Gravity.CENTER
                }
            }
        }
    }

    private val session: TerminalSession?
        get() = if (currentIndex in sessions.indices) sessions[currentIndex] else null

    private fun writeShellConfigs(rootfsDir: File) {
        try {
            val osRelease = try { File(rootfsDir, "etc/os-release").readText() } catch (_: Exception) { "" }
            val distro = when {
                osRelease.contains("Alpine", ignoreCase = true) -> "alpine"
                osRelease.contains("Ubuntu", ignoreCase = true) -> "ubuntu"
                osRelease.contains("Debian", ignoreCase = true) -> "debian"
                File(rootfsDir, "etc/fedora-release").exists() || osRelease.contains("Fedora", ignoreCase = true) -> "fedora"
                osRelease.contains("Void", ignoreCase = true) -> "void"
                osRelease.contains("Manjaro", ignoreCase = true) -> "manjaro"
                osRelease.contains("Arch Linux", ignoreCase = true) -> "arch"
                osRelease.contains("Artix", ignoreCase = true) -> "artix"
                osRelease.contains("Rocky Linux", ignoreCase = true) -> "rocky"
                osRelease.contains("AlmaLinux", ignoreCase = true) -> "almalinux"
                osRelease.contains("Kali", ignoreCase = true) -> "kali"
                File(rootfsDir, "etc/debian_version").exists() -> "debian"
                else -> "unknown"
            }

            val (pmUpdate, pmInstall, pmQuiet) = when (distro) {
                "alpine" -> Triple("apk update", "apk add", "-q")
                "debian", "ubuntu", "kali" -> Triple("apt-get update -qq", "DEBIAN_FRONTEND=noninteractive apt-get install -y", "-qq")
                "fedora", "rocky", "almalinux" -> Triple("dnf check-update || true", "dnf install -y", "-q")
                "void" -> Triple("xbps-install -Su", "xbps-install -S", "")
                "arch", "artix" -> Triple("pacman -Syy --noconfirm", "pacman -S --noconfirm --needed glibc gcc-libs", "")
                "manjaro" -> Triple("pacman -Syy --noconfirm", "pacman -S --noconfirm", "")
                else -> Triple(":", ":", "")
            }

            val rootDir = File(rootfsDir, "root")
            rootDir.mkdirs()

            val bashrcFile = File(rootDir, ".bashrc")
            if (!bashrcFile.exists()) {
                bashrcFile.writeText("""# ~/.bashrc
export TERM=xterm-256color
stty erase ^?
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
shopt -s checkwinsize histappend
HISTSIZE=1000
HISTFILESIZE=2000
PS1='\[\e[1;32m\]\u@redterm\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\$ '
alias ls='ls --color=auto'
alias ll='ls -lah --color=auto'
alias la='ls -A --color=auto'
alias grep='grep --color=auto'
alias ..='cd ..'
alias rm='rm -i'
alias cp='cp -i'
alias mv='mv -i'
""")
            }
            val startupFile = File(rootDir, ".startup")
            if (!startupFile.exists()) {
                startupFile.writeText("""if [ ! -f /root/.init_done ]; then
    echo '>>> First-time distro setup...'
    if $pmUpdate 2>/dev/null && $pmInstall $pmQuiet nano wget sudo bash openssl 2>/dev/null; then
        touch /root/.init_done
        echo '>>> Setup complete.'
    else
        echo '>>> Setup was interrupted or failed - starting a repair shell.'
        echo ">>> Run manually: $pmUpdate && $pmInstall $pmQuiet nano wget sudo bash openssl"
    fi
fi
if command -v bash >/dev/null 2>&1; then
    exec bash -i
fi
""")
            }
        } catch (e: Exception) {
            android.util.Log.w("TerminalActivity", "writeShellConfigs failed: ${e.message}")
        }
    }

    private fun createNewSession() {
        val prefs = prefs()
        val scrollback = Prefs.SCROLLBACK_ROWS[prefs.getInt(Prefs.KEY_SCROLLBACK, Prefs.SCROLLBACK_DEFAULT).coerceIn(0, 9)]
        val rootfsDir = DistroInstaller(applicationContext).getRootfsDir(distroName)
        if (!rootfsDir.exists()) {
            showError("Distro $distroName not installed.\nRun installer first.")
            return
        }

        val backend = terminalBackend ?: TerminalBackend(terminalView, this).also {
            terminalBackend = it
            terminalView.setTerminalViewClient(it)
            wireBackend(it)
        }

        // Heavy rootfs prep + launch-script write off the UI thread so session
        // creation never blocks typing/rendering. The TerminalSession itself
        // (and view attach) is created back on the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            val repairLog = DistroInstaller(applicationContext).repairRootfs(rootfsDir)
            if (repairLog.contains("WARN") || repairLog.contains("missing")) {
                android.util.Log.w("TerminalActivity", "Rootfs issues:\n$repairLog")
            }

            // Ensure /tmp, /dev/shm, /run/shm and executable binaries in rootfs.
            File(rootfsDir, "tmp").mkdirs()
            File(rootfsDir, "dev/shm").mkdirs()
            File(rootfsDir, "run").mkdirs()
            File(rootfsDir, "run/shm").mkdirs()
            val busybox = File(rootfsDir, "bin/busybox")
            if (busybox.exists() && !busybox.canExecute()) busybox.setExecutable(true, false)
            for (name in listOf("sh", "ash", "bash")) {
                val f = File(rootfsDir, "bin/$name")
                if (f.exists() && !f.canExecute()) f.setExecutable(true, false)
            }

            writeShellConfigs(rootfsDir)

            // ---- Distro init & proot launch ----
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val prootBin = com.redtermapp.proot.ProotInstaller.getProotPath(this@TerminalActivity)
                ?: "$nativeLibDir/libproot.so"
            val prootLoader = "$nativeLibDir/libloader.so"
            val prootLoader32 = "$nativeLibDir/libloader32.so"
            val ldr32 = if (File(prootLoader32).exists()) "export PROOT_LOADER_32=$prootLoader32\n" else ""
            val rp = rootfsDir.absolutePath
            val startHost = filesDir.absolutePath
            val sessionId = java.util.UUID.randomUUID().toString().replace("-", "").take(Prefs.SESSION_ID_LENGTH)
            val launchSh = File(filesDir, "launch_$sessionId.sh")
            launchSh.parentFile?.mkdirs()
            val tz = java.util.TimeZone.getDefault().id
            launchSh.writeText("""#!/system/bin/sh
export HOME=/root
export TERM=xterm-256color
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export TZ="$tz"
export TMPDIR=/tmp
export PATH=/system/bin:/system/xbin:/bin:/sbin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin
export ENV=/root/.startup
# Libuv (Node.js / opencode) I/O concurrency. Inherited by the guest shell.
export UV_THREADPOOL_SIZE=${Prefs.UV_THREADPOOL_SIZE}
export PROOT_LOADER=$prootLoader
${ldr32}export PROOT_TMP_DIR=$rp/tmp
mkdir -p "$rp/tmp" "$rp/dev/shm" "$rp/run/shm"
# Raise soft resource limits so heavy programs (compilers, AI CLIs, servers)
# get enough file descriptors and processes. Silently no-ops if already higher.
ulimit -n ${Prefs.ULIMIT_NOFILE} 2>/dev/null
ulimit -u ${Prefs.ULIMIT_NPROC} 2>/dev/null
exec $prootBin -0 -L -r "$rp" -w /root --link2symlink --sysvipc --ashmem-memfd --kill-on-exit \
    -b /dev -b /proc -b /sys -b /system -b /apex -b /linkerconfig/ld.config.txt \
    -b /sdcard -b /storage -b /mnt \
    /bin/sh -i 2>&1
""")
            launchSh.setExecutable(true, false)

            val args = arrayOf("-c", launchSh.absolutePath)

            withContext(Dispatchers.Main) {
                val s = TerminalSession(
                    "/system/bin/sh", startHost,
                    args, emptyArray(),
                    scrollback,
                    backend
                )
                s.mSessionName = distroName

                wireBackend(backend)

                sessionLaunchScripts[s] = launchSh.absolutePath
                sessionModel.addSession(s)
                currentFontSize = prefs.getInt("font_size", 20)
                terminalView.setTextSize(currentFontSize)
                applyFontFromPrefs(prefs)
                terminalView.setBackgroundColor(themeColor(R.attr.terminalBg, 0xFF1E1E2E.toInt()))
                terminalView.attachSession(s)
                terminalView.onScreenUpdated()

                terminalView.post {
                    terminalView.requestFocus()
                    terminalView.isFocusableInTouchMode = true
                }

                updateDrawer()
                RedTermWidgetProvider.updateAll(this@TerminalActivity)
            }
        }
    }

    private fun switchToSession(index: Int) {
        if (index !in sessions.indices || index == currentIndex) return
        sessionModel.switchToSession(index)
        terminalView.attachSession(sessions[index])
        terminalView.onScreenUpdated()
        updateDrawer()
    }
    private fun handleSessionFinished(finishedSession: TerminalSession) {
        val idx = sessions.indexOf(finishedSession)
        if (idx < 0) return
        sessionLaunchScripts.remove(finishedSession)?.let { path -> File(path).delete() }
        sessionModel.removeSession(idx)
        if (sessions.isEmpty()) {
            finish()
        } else {
            val safeIdx = currentIndex.coerceIn(0, sessions.lastIndex)
            terminalView.attachSession(sessions[safeIdx])
            terminalView.onScreenUpdated()
            updateDrawer()
        }
        RedTermWidgetProvider.updateAll(this)
    }

    private fun sweepStaleLaunchScripts() {
        val active = sessionLaunchScripts.values.toHashSet()
        filesDir.listFiles { _, name -> name.startsWith("launch_") && name.endsWith(".sh") }
            ?.forEach { if (it.absolutePath !in active) it.delete() }
    }

    private fun closeSession(index: Int) {
        if (sessions.size <= 1) return
        sessionModel.removeSession(index)
        if (currentIndex in sessions.indices) {
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
        }
        updateDrawer()
    }

    private fun updateDrawer() {
        findViewById<TextView>(R.id.session_count).text = sessions.size.toString()
        sessionListContainer.removeAllViews()
        if (sessions.isEmpty()) {
            sessionListContainer.addView(TextView(this).apply {
                text = "No sessions"
                setTextColor(mutedTextColor())
                textSize = 13f
                setPadding(16, 20, 16, 20)
            })
            return
        }
        for (i in sessions.indices) {
            val bgColor = if (i == currentIndex)
                themeColor(R.attr.extraKeysBg, 0xFF181825.toInt())
            else 0
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(4, 4, 4, 4) }
                setCardBackgroundColor(bgColor)
                radius = 10f
                cardElevation = 0f
                isClickable = true
                isFocusable = true
                isLongClickable = true
                setOnClickListener { switchToSession(i); hideSessionsPanel() }
                setOnLongClickListener {
                    val currentLabel = sessions[i].mSessionName.ifEmpty { "session ${i + 1}" }
                    val input = android.widget.EditText(this@TerminalActivity).apply { setText(currentLabel) }
                    androidx.appcompat.app.AlertDialog.Builder(this@TerminalActivity)
                        .setTitle("Rename session")
                        .setView(input)
                        .setPositiveButton("Rename") { _, _ ->
                            val newName = input.text.toString().trim()
                            if (newName.isNotEmpty()) {
                                sessions[i].mSessionName = newName
                                updateDrawer()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12, 10, 8, 10)
                    addView(TextView(context).apply {
                        text = sessions[i].mSessionName.ifEmpty { "session ${i + 1}" }
                        setTextColor(themeColor(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        isClickable = false
                        isFocusable = false
                        isLongClickable = false
                    })
                    val dotSize = dp(12)
                    addView(android.view.View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                            gravity = Gravity.CENTER
                            setMargins(0, 0, dp(12), 0)
                        }
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            if (i == currentIndex) {
                                setColor(0xFFA6E3A1.toInt())
                            } else {
                                setColor(0x00000000)
                                setStroke(dp(2), mutedTextColor())
                            }
                        }
                    })
                    addView(ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER }
                        setImageDrawable(
                            androidx.appcompat.content.res.AppCompatResources.getDrawable(
                                context, android.R.drawable.ic_menu_close_clear_cancel
                            )
                        )
                        imageTintList = android.content.res.ColorStateList.valueOf(mutedTextColor())
                        setOnClickListener { closeSession(i) }
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                    })
                })
            }
            sessionListContainer.addView(card)
        }
    }

    private fun exportCurrentOutput() {
        val s = session ?: return
        hideSessionsPanel()
        Thread {
            try {
                val text = s.emulator.getScreen().getTranscriptText()
                var dir = File(
                    android.os.Environment.getExternalStorageDirectory(), "RedTerm/exports"
                )
                dir.mkdirs()
                if (!dir.exists()) dir = File(filesDir, "exports").apply { mkdirs() }
                val f = File(dir, "${distroName}-${System.currentTimeMillis()}.txt")
                f.writeText(text)
                runOnUiThread {
                    Toast.makeText(this, "Exported: ${f.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun copySelectedText() {
        if (terminalView.isSelectingText) {
            val text = terminalView.getSelectedText()
            if (!text.isNullOrEmpty()) {
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
                terminalView.stopTextSelectionMode()
                Toast.makeText(this, "Copied ${text.length} chars", Toast.LENGTH_SHORT).show()
                return
            }
        }
        session?.let {
            val text = it.emulator.getScreen().getTranscriptText()
            val clip = getSystemService(android.content.ClipboardManager::class.java)
            clip.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
            Toast.makeText(this, "Copied entire output (${text.length} chars)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteClipboard() {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        val text = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        terminalView.mEmulator?.paste(text)
    }

    private fun showError(msg: String) {
        val errorFile = File(cacheDir, "opencode_error.txt")
        errorFile.writeText(msg)

        terminalView.setTextSize(14)
        terminalView.setBackgroundColor(themeColor(R.attr.terminalBg, 0xFF1E1E2E.toInt()))
        val backend = TerminalBackend(terminalView, this)
        terminalView.setTerminalViewClient(backend)
        backend.onEmulatorReady = { applyEmulatorColors(terminalView) }
        val s = TerminalSession(
            "/system/bin/toybox", filesDir.absolutePath,
            arrayOf("cat", errorFile.absolutePath), emptyArray(),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS, backend
        )
        s.mSessionName = "Error"
        terminalView.attachSession(s)
        terminalView.onScreenUpdated()
        terminalView.post { terminalView.requestFocus() }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, TerminalService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("TerminalActivity", "Foreground service failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        sendServiceAction(TerminalService.ACTION_AUTO_RELEASE)
        if (!com.redtermapp.util.StoragePermission.isAccessible(this)) {
            val prefs = prefs()
            val lastAsk = prefs.getLong("storage_ask_time", 0L)
            if (System.currentTimeMillis() - lastAsk > Prefs.PERMISSION_ASK_THROTTLE_MS) {
                prefs.edit().putLong("storage_ask_time", System.currentTimeMillis()).apply()
                com.redtermapp.util.StoragePermission.requestAccess(this)
            }
        }
        terminalView.requestFocus()
        terminalView.onScreenUpdated()
        updateModifierButtons()
        updateExtraKeysVisibility()
        RedTermWidgetProvider.updateAll(this)
    }

    override fun onPause() {
        super.onPause()
        if (sessions.isNotEmpty()) {
            sendServiceAction(TerminalService.ACTION_AUTO_WAKE)
        }
    }

    private fun sendServiceAction(action: String) {
        try {
            startService(Intent(this, TerminalService::class.java).apply { this.action = action })
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (sessions.isNotEmpty()) {
            val newDistro = intent.getStringExtra(EXTRA_DISTRO)
            val target = if (newDistro != null)
                sessions.indexOfFirst { it.mSessionName.equals(newDistro, ignoreCase = true) }
            else -1
            if (target >= 0) {
                sessionModel.switchToSession(target)
                terminalView.attachSession(sessions[target])
            } else if (currentIndex in sessions.indices) {
                terminalView.attachSession(sessions[currentIndex])
            }
            terminalView.onScreenUpdated()
            terminalView.requestFocus()
        }
    }

    override fun onDestroy() {
        RedTermWidgetProvider.updateAll(this)
        unregisterReceiver(nightReceiver)
        if (sessions.isEmpty()) {
            stopService(Intent(this, TerminalService::class.java))
            TerminalViewModel.clearIfEmpty()
        }
        terminalBackend?.onSessionFinished = null
        terminalBackend = null
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.x
                swipeStartY = ev.y
            }
            android.view.MotionEvent.ACTION_UP -> {
                val deltaX = ev.x - swipeStartX
                val deltaY = ev.y - swipeStartY
                if (Math.abs(deltaX) > dp(80) && Math.abs(deltaX) > Math.abs(deltaY) * 2) {
                    if (deltaX < 0) {
                        switchExtraKeysMode(true)
                    } else {
                        switchExtraKeysMode(false)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentIndex !in sessions.indices) return super.dispatchKeyEvent(event)
        val hadModifier = ctrlActive || altActive
        @Suppress("DEPRECATION")
        val handled = when (event.action) {
            KeyEvent.ACTION_DOWN -> terminalView.onKeyDown(event.keyCode, event) || super.dispatchKeyEvent(event)
            KeyEvent.ACTION_UP -> terminalView.onKeyUp(event.keyCode, event) || super.dispatchKeyEvent(event)
            KeyEvent.ACTION_MULTIPLE -> {
                if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                    @Suppress("DEPRECATION") session?.write(event.characters ?: ""); true
                } else super.dispatchKeyEvent(event)
            }
            else -> super.dispatchKeyEvent(event)
        }
        if (hadModifier && event.action != KeyEvent.ACTION_UP &&
            event.keyCode != KeyEvent.KEYCODE_CTRL_LEFT &&
            event.keyCode != KeyEvent.KEYCODE_CTRL_RIGHT &&
            event.keyCode != KeyEvent.KEYCODE_ALT_LEFT &&
            event.keyCode != KeyEvent.KEYCODE_ALT_RIGHT
        ) {
            consumeModifiers()
        }
        return handled
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        menu.add(0, 12, 0, "Copy")
        menu.add(0, 13, 0, "Paste")
        menu.add(0, 14, 0, "Export")
        menu.add(0, 3, 0, "Font +")
        menu.add(0, 4, 0, "Font -")
        menu.add(0, 5, 0, "Reset")
        val fontSub = menu.addSubMenu(0, 7, 0, "Fonts")
        for ((id, name) in Prefs.FONT_MENU_IDS) {
            fontSub.add(0, id, 0, name)
        }
        customFontFiles().forEachIndexed { i, f ->
            fontSub.add(0, Prefs.CUSTOM_FONT_MENU_BASE + i, 0, "${fontDisplayName(f.name)} (custom)")
        }

        val themeSub = menu.addSubMenu(0, 6, 0, "Theme")
        themeSub.add(0, 61, 0, "Catppuccin Dark")
        themeSub.add(0, 62, 0, "Green Terminal")
        themeSub.add(0, 63, 0, "Light")
        themeSub.add(0, 69, 0, "Red Terminal")
        themeSub.add(0, 68, 0, "AMOLED Black")
        themeSub.add(0, 64, 0, "Dracula")
        themeSub.add(0, 65, 0, "Nord")
        themeSub.add(0, 66, 0, "Tokyo Night")
        themeSub.add(0, 67, 0, "Gruvbox Dark")
        themeSub.add(0, 70, 0, "Custom")
        themeSub.add(0, 79, 0, "Dynamic")
        menu.add(0, 9, 0, "Snippets")
    }

    override fun onContextMenuClosed(menu: Menu) {
        terminalView.onContextMenuClosed(menu)
        super.onContextMenuClosed(menu)
    }

    private fun resolveTerminalColors(): Triple<Int, Int, Int> {
        val prefs = prefs()
        val themeName = prefs.getString("theme", "amoled") ?: "amoled"
        val dynamic = if (themeName == "dynamic") dynamicTerminalColors() else null
        val bg = when {
            themeName == "custom" -> prefs.getInt("custom_bg", 0xFF1E1E2E.toInt())
            dynamic != null -> dynamic.first
            else -> AppTheme.resolveThemeColor(this, prefs, R.attr.terminalBg, 0xFF1E1E2E.toInt())
        }
        val extraBg = when {
            themeName == "custom" -> prefs.getInt("custom_bg", 0xFF0A0A0A.toInt())
            dynamic != null -> dynamic.second
            else -> AppTheme.resolveThemeColor(this, prefs, R.attr.extraKeysBg, 0xFF181825.toInt())
        }
        val textColor = when {
            themeName == "custom" -> prefs.getInt("custom_text", 0xFFCDD6F4.toInt())
            dynamic != null -> dynamic.third
            else -> AppTheme.resolveThemeColor(this, prefs, R.attr.terminalText, 0xFFCDD6F4.toInt())
        }
        return Triple(bg, extraBg, textColor)
    }

    private fun applyEmulatorColors(view: TerminalView) {
        val emulator = view.mEmulator ?: return
        val (bg, _, textColor) = resolveTerminalColors()
        val palette = emulator.mColors.mCurrentColors
        palette[TextStyle.COLOR_INDEX_FOREGROUND] = textColor
        palette[TextStyle.COLOR_INDEX_BACKGROUND] = bg
        palette[TextStyle.COLOR_INDEX_CURSOR] = textColor
        view.invalidate()
    }

    private fun applyTerminalTheme(themeName: String) {
        val prefs = prefs()
        prefs.edit().putString("theme", themeName).apply()
        NightModeReceiver.notifyChanged(this, prefs)
        val (bg, extraBg, textColor) = resolveTerminalColors()

        val opacity = prefs.getInt("terminal_opacity", 10).coerceIn(0, 10)
        val alpha = (opacity * 25.5).toInt().coerceIn(0, 255)
        val bgWithAlpha = (bg and 0x00FFFFFF) or (alpha shl 24)
        val extraBgWithAlpha = (extraBg and 0x00FFFFFF) or (alpha shl 24)
        terminalView.setBackgroundColor(bgWithAlpha)
        drawerLayout.setBackgroundColor(bg)
        val row1 = findViewById<LinearLayout>(R.id.extra_keys_container).apply { setBackgroundColor(extraBgWithAlpha) }
        val row2 = findViewById<LinearLayout>(R.id.extra_keys_container_row2).apply { setBackgroundColor(extraBgWithAlpha) }
        for (i in 0 until row1.childCount) (row1.getChildAt(i) as? android.widget.TextView)?.setTextColor(textColor)
        for (i in 0 until row2.childCount) (row2.getChildAt(i) as? android.widget.TextView)?.setTextColor(textColor)
        applyEmulatorColors(terminalView)
    }

    private fun dynamicTerminalColors(): Triple<Int, Int, Int>? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        return try {
            Triple(
                getColor(android.R.color.system_neutral1_1000),
                getColor(android.R.color.system_neutral1_900),
                getColor(android.R.color.system_neutral1_100)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun resetTerminalDefaults(prefs: android.content.SharedPreferences) {
        session?.reset()
        prefs.edit().putString("font", "monospace").apply()
        applyFontFromPrefs(prefs)
        currentFontSize = 20
        prefs.edit().putInt("font_size", 20).apply()
        terminalView.setTextSize(20)
        applyTerminalTheme("amoled")
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val prefs = prefs()
        return when (item.itemId) {
            12 -> { copySelectedText(); true }
            13 -> { pasteClipboard(); true }
            14 -> { exportCurrentOutput(); true }
            3 -> { currentFontSize = (currentFontSize + 2).coerceAtMost(36); terminalView.setTextSize(currentFontSize); true }
            4 -> { currentFontSize = (currentFontSize - 2).coerceAtLeast(8); terminalView.setTextSize(currentFontSize); true }
             5 -> { resetTerminalDefaults(prefs); true }
              61 -> { applyTerminalTheme("default"); true }
              62 -> { applyTerminalTheme("green"); true }
              63 -> { applyTerminalTheme("light"); true }
              69 -> { applyTerminalTheme("red"); true }
              68 -> { applyTerminalTheme("amoled"); true }
              64 -> { applyTerminalTheme("dracula"); true }
              65 -> { applyTerminalTheme("nord"); true }
              66 -> { applyTerminalTheme("tokyo"); true }
              67 -> { applyTerminalTheme("gruvbox"); true }
               70 -> { applyTerminalTheme("custom"); true }
               79 -> { applyTerminalTheme("dynamic"); true }
               in Prefs.FONT_MENU_IDS.map { it.first } -> {
                   val fontName = Prefs.FONT_MENU_IDS.first { it.first == item.itemId }.second
                   prefs.edit().putString(Prefs.KEY_FONT, fontName).apply(); applyFontFromPrefs(prefs); true
               }
                in Prefs.CUSTOM_FONT_MENU_BASE..(Prefs.CUSTOM_FONT_MENU_BASE + 999) -> {
                    val fontIdx = item.itemId - Prefs.CUSTOM_FONT_MENU_BASE
                    val fontName = customFontFiles().getOrNull(fontIdx)?.name ?: ""
                    prefs.edit().putString("font", "custom:$fontName").apply()
                    applyFontFromPrefs(prefs); true
                }
                  9 -> { showSnippetsDialog(); true }
             else -> super.onContextItemSelected(item)
        }
    }

    private val fontCache = HashMap<String, android.graphics.Typeface?>()

    private fun loadFont(assetPath: String): android.graphics.Typeface? =
        fontCache.getOrPut(assetPath) {
            try {
                android.graphics.Typeface.createFromAsset(assets, assetPath)
            } catch (_: Exception) {
                null
            }
        }

    private fun applyFontFromPrefs(prefs: android.content.SharedPreferences) {
        val tf = fontFromPrefs(prefs)
        terminalView.setTypeface(tf)
    }

    private fun fontFromPrefs(prefs: android.content.SharedPreferences): android.graphics.Typeface {
        val fontName = prefs.getString("font", "monospace")
        val tf = when {
            fontName != null && fontName.startsWith("custom:") ->
                try {
                    android.graphics.Typeface.createFromFile(
                        File(filesDir, "fonts/${fontName.removePrefix("custom:")}")
                    )
                } catch (_: Exception) {
                    null
                }
            else -> {
                val asset = Prefs.FONT_ASSET_MAP[fontName]
                if (asset != null) loadFont(asset) else android.graphics.Typeface.MONOSPACE
            }
        }
        return tf ?: android.graphics.Typeface.MONOSPACE
    }

    private fun applyFontToView(view: com.termux.view.TerminalView, prefs: android.content.SharedPreferences) {
        view.setTypeface(fontFromPrefs(prefs))
    }

    private fun applyTheme() {
        AppTheme.apply(this)
        val prefs = prefs()
        if (NightModeReceiver.effectiveTheme(prefs) == "dynamic" && android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
            } catch (_: Exception) {}
        }
    }

    private fun showSnippetsDialog() {
        val prefs = prefs()
        val names = mutableListOf<String>()
        val contents = mutableListOf<String>()
        try {
            val json = prefs.getString(Prefs.KEY_SNIPPETS, "[]") ?: "[]"
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                names.add(obj.getString("name"))
                contents.add(obj.getString("content"))
            }
        } catch (e: org.json.JSONException) {
            android.util.Log.w("TerminalActivity", "Malformed snippets pref", e)
        }

        val items = if (names.isEmpty()) arrayOf("(no snippets — tap + to add)") else names.toTypedArray()

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Snippets")
        builder.setItems(items) { _, which ->
            if (names.isNotEmpty() && which < contents.size) {
                val content = contents[which]
                val session = terminalView.mTermSession ?: return@setItems
                session.write(content.toByteArray(), 0, content.length)
            }
        }
        builder.setPositiveButton("+ Add") { _, _ -> showAddSnippetDialog() }
        builder.setNegativeButton("Edit") { _, _ -> showEditSnippetsDialog() }
        builder.show()
    }

    private fun showAddSnippetDialog() {
        val input = android.widget.EditText(this)
        input.hint = "command or text"
        input.setTextColor(0xFFCDD6F4.toInt())
        input.setHintTextColor(hintColor())

        val nameInput = android.widget.EditText(this)
        nameInput.hint = "snippet name"
        nameInput.setTextColor(0xFFCDD6F4.toInt())
        nameInput.setHintTextColor(hintColor())

        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(48, 16, 48, 16)
        layout.addView(nameInput)
        layout.addView(input)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Add Snippet")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val content = input.text.toString()
                if (name.isNotEmpty() && content.isNotEmpty()) {
                    saveSnippet(name, content)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveSnippet(name: String, content: String) {
        val prefs = prefs()
        val arr = try {
            org.json.JSONArray(prefs.getString(Prefs.KEY_SNIPPETS, "[]") ?: "[]")
        } catch (e: org.json.JSONException) {
            org.json.JSONArray()
        }
        val obj = org.json.JSONObject()
        obj.put("name", name)
        obj.put("content", content)
        arr.put(obj)
        prefs.edit().putString(Prefs.KEY_SNIPPETS, arr.toString()).apply()
    }

    private fun showEditSnippetsDialog() {
        val prefs = prefs()
        val arr = try {
            org.json.JSONArray(prefs.getString(Prefs.KEY_SNIPPETS, "[]") ?: "[]")
        } catch (e: org.json.JSONException) {
            android.widget.Toast.makeText(this, "No snippets to edit", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            try {
                names.add(arr.getJSONObject(i).getString("name"))
            } catch (_: org.json.JSONException) {}
        }

        if (names.isEmpty()) {
            android.widget.Toast.makeText(this, "No snippets to edit", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Edit / Delete Snippets")
        builder.setItems(names.toTypedArray()) { _, which ->
            if (which < names.size) {
                android.app.AlertDialog.Builder(this)
                    .setTitle(names[which])
                    .setMessage("What to do with this snippet?")
                    .setPositiveButton("Delete") { _, _ ->
                        arr.remove(which)
                        prefs.edit().putString("snippets", arr.toString()).apply()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        builder.show()
    }
}
