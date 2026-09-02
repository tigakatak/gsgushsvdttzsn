package com.redt.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ContextMenu
import android.view.Gravity
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.redt.R
import com.redt.distro.DistroInstaller
import com.redt.distro.DistroRegistry
import com.redt.proot.ProotInstaller
import com.redt.session.terminalSessionStore
import com.redt.service.TerminalService
import com.redt.util.Format
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalActivity : AppCompatActivity() {

    private val distroName = DistroRegistry.alpine.name
    private lateinit var terminalView: TerminalView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sessionListContainer: LinearLayout
    private lateinit var extraKeysWrapper: LinearLayout
    private lateinit var extraKeysPager: ViewPager2
    private var rootContainer: LinearLayout? = null
    private var terminalBgLayer: android.view.View? = null
    private var row1Container: LinearLayout? = null
    private var row2Container: LinearLayout? = null
    private var inputField: EditText? = null

    private var terminalBackend: TerminalBackend? = null
    private val installer by lazy { DistroInstaller(applicationContext) }

    internal fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
    }

    private val sessionStore by lazy { terminalSessionStore }
    private val sessions: List<TerminalSession> get() = sessionStore.sessions.value
    private val currentIndex: Int get() = sessionStore.currentIndex.value


    private val requestNotificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Notifications will be suppressed; the terminal service will still run",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private fun wireBackend(backend: TerminalBackend) {
        backend.onSessionFinished = { finishedSession -> handleSessionFinished(finishedSession) }
        backend.onModifierConsumed = { consumeModifiers() }
        backend.onEmulatorReady = { applyEmulatorColors(backend.view) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        setupImeVisibilityListener()

        if (!com.redt.util.StoragePermission.isAccessible()) {
            Toast.makeText(
                this,
                "RedT needs All files access to use /storage/emulated/0 in the terminal",
                Toast.LENGTH_LONG
            ).show()
            com.redt.util.StoragePermission.requestAccess(this)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // Clean up disk usage not owned by any visible feature: rootfs husks
        // from interrupted installs and stale download files.
        lifecycleScope.launch(Dispatchers.IO) {
            installer.sweepOrphanFiles()
        }

        terminalView = findViewById(R.id.terminal_view)
        drawerLayout = findViewById(R.id.drawer_layout)
        sessionListContainer = findViewById(R.id.session_list_container)
        extraKeysWrapper = findViewById(R.id.extra_keys_wrapper)
        rootContainer = findViewById(R.id.root_container)
        terminalBgLayer = findViewById(R.id.terminal_bg_layer)
        extraKeysPager = findViewById(R.id.extra_keys_pager)
        extraKeysPager.offscreenPageLimit = 1
        extraKeysPager.adapter = ExtraKeysPagerAdapter(
            activity = this,
            onKeysPageReady = { row1, row2 ->
                row1Container = row1
                row2Container = row2
                setupExtraKeysRow1()
                setupExtraKeysRow2()
                updateModifierButtons()
            },
            onInputPageReady = { et -> inputField = et }
        )

        drawerLayout.setDrawerLockMode(
            DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            androidx.core.view.GravityCompat.START
        )
        val closeDrawerOnBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { hideSessionsPanel() }
        }
        onBackPressedDispatcher.addCallback(this, closeDrawerOnBack)
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) { closeDrawerOnBack.isEnabled = true }
            override fun onDrawerClosed(drawerView: View) { closeDrawerOnBack.isEnabled = false }
        })

        registerForContextMenu(terminalView)

        val prefs = prefs()

        updateDistroSizeLabel()

        if (prefs.getBoolean(Prefs.KEY_WAKELOCK, Prefs.WAKELOCK_DEFAULT)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (prefs.getBoolean("autohide_keys", false)) {
            toggleExtraKeys(false)
        }

        findViewById<TextView>(R.id.new_session_button).setOnClickListener {
            requestNewSession()
        }

        observeSessions()
        lifecycleScope.launch {
            sessionStore.exitSignal.collect {
                // Exit from the notification means "quit RedT entirely":
                // close every activity in the task, not just this screen.
                exitingApp = true
                finishAffinity()
            }
        }
        if (sessions.isEmpty()) {
            requestNewSession()
        } else {
            startForegroundService()
            val backend = ensureTerminalBackend()
            for (s in sessions) {
                sessionStore.attachClient(s, backend)
            }
            backend.applyFontSize()
            terminalView.setBackgroundColor(resolveTerminalColors().first)
            val current = sessions[currentIndex.coerceIn(0, sessions.lastIndex)]
            sessionStore.switchToSession(current)
            terminalView.attachSession(current)
            terminalView.onScreenUpdated()
            terminalView.post {
                terminalView.requestFocus()
                terminalView.isFocusableInTouchMode = true
            }
            updateDrawer()
        }
    }

    private fun ensureTerminalBackend(): TerminalBackend =
        terminalBackend ?: TerminalBackend(terminalView, this).also {
            terminalBackend = it
            terminalView.setTerminalViewClient(it)
            wireBackend(it)
        }

    private var exitingApp = false
    private var sawActiveSession = false

    private fun observeSessions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(sessionStore.sessions, sessionStore.currentIndex) { active, index ->
                    active to index
                }.collect { (active, index) ->
                    val backend = ensureTerminalBackend()
                    if (terminalView.mRenderer == null) {
                        backend.applyFontSize()
                        terminalView.setBackgroundColor(resolveTerminalColors().first)
                    }
                    active.forEach { sessionStore.attachClient(it, backend) }
                    if (active.isEmpty()) {
                        // The session list can only become empty after the store
                        // previously held sessions (the very first emission is
                        // empty too). onSessionFinished races with the store
                        // update, so decide navigation here, not in the callback.
                        if (sawActiveSession && !exitingApp) {
                            exitApp()
                        }
                        updateDrawer()
                        return@collect
                    }
                    sawActiveSession = true
                    clearErrorOverlay()
                    val safeIndex = index.coerceIn(active.indices)
                    if (terminalView.mTermSession !== active[safeIndex]) {
                        terminalView.attachSession(active[safeIndex])
                        terminalView.onScreenUpdated()
                    }
                    updateDrawer()
                    RedTWidgetProvider.updateAll(this@TerminalActivity)
                }
            }
        }
    }

    private fun isRepeatableKey(label: String): Boolean {
        return label in listOf(
            "\u25B2", "UP", "\u25BC", "DOWN", "\u25C0", "LEFT", "\u25B6", "RIGHT",
            "HOME", "END", "DEL", "INS", "\u232B", "BACKSPACE"
        )
    }

    private fun updateDistroSizeLabel() {
        val rootfsDir = installer.getRootfsDir(distroName)
        val sizeLabel = findViewById<TextView>(R.id.distro_size_label)
        val cached = Format.cachedSize(rootfsDir)
        sizeLabel.text = if (cached != null) {
            "$distroName (${Format.size(cached)})"
        } else {
            distroName
        }
        val weakLabel = java.lang.ref.WeakReference(sizeLabel)
        Format.dirSizeAsync(rootfsDir) { bytes ->
            val view = weakLabel.get() ?: return@dirSizeAsync
            if (view.isAttachedToWindow) {
                view.text = "$distroName (${Format.size(bytes)})"
            }
        }
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
            "TAB" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_TAB, currentKeyMod()); Unit },
            "ALT" to { toggleAlt() },
            "SHIFT" to { toggleShift() },
            "CTRL" to { toggleCtrl() },
            "\u25B2" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, currentKeyMod()); Unit },
            "UP" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, currentKeyMod()); Unit },
            "\u25BC" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, currentKeyMod()); Unit },
            "DOWN" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, currentKeyMod()); Unit },
            "\u25C0" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, currentKeyMod()); Unit },
            "LEFT" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, currentKeyMod()); Unit },
            "\u25B6" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, currentKeyMod()); Unit },
            "RIGHT" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, currentKeyMod()); Unit },
            "HOME" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_MOVE_HOME, currentKeyMod()); Unit },
            "END" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_MOVE_END, currentKeyMod()); Unit },
            "INS" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_INSERT, currentKeyMod()); Unit },
            "DEL" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_FORWARD_DEL, currentKeyMod()); Unit },
            "\u232B" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DEL, currentKeyMod()); Unit },
            "BACKSPACE" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DEL, currentKeyMod()); Unit },
            "\u2014" to { session?.write("-"); Unit },
        )
        val action = actions.firstOrNull { it.first == label }?.second
            ?: {
                session?.write(label)
                Unit
            }
        if (label == "CTRL" || label == "ALT" || label == "SHIFT") return action
        return {
            if (session != null || label == "\u2630" || label == "MENU") {
                action()
                consumeModifiers()
            }
        }
    }

    private fun currentKeyMod(): Int {
        var mod = 0
        if (ctrlActive) mod = mod or com.termux.terminal.KeyHandler.KEYMOD_CTRL
        if (altActive) mod = mod or com.termux.terminal.KeyHandler.KEYMOD_ALT
        if (shiftActive) mod = mod or com.termux.terminal.KeyHandler.KEYMOD_SHIFT
        return mod
    }

    private fun setupExtraKeysRow1() {
        val container = row1Container ?: return
        for (label in extraKeyLabels().first) {
            container.addView(createKeyButton(label, keyAction(label)))
        }
    }

    private fun setupExtraKeysRow2() {
        val container = row2Container ?: return
        for (label in extraKeyLabels().second) {
            container.addView(createKeyButton(label, keyAction(label)))
        }
    }

    private var appliedExtraKeyLabels: Pair<List<String>, List<String>>? = null

    /**
     * Rebuilds the extra key rows when their configuration changed while this
     * activity was stopped (e.g. edited in Settings). The rows are otherwise
     * only built once when the pager creates its pages.
     */
    private fun syncExtraKeysRows() {
        val labels = extraKeyLabels()
        if (labels == appliedExtraKeyLabels) return
        appliedExtraKeyLabels = labels
        row1Container?.removeAllViews()
        row2Container?.removeAllViews()
        setupExtraKeysRow1()
        setupExtraKeysRow2()
        updateModifierButtons()
    }

    private var ctrlActive = false
    private var altActive = false
    private var shiftActive = false
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

    private fun toggleShift() {
        shiftActive = !shiftActive
        terminalBackend?.setShift(shiftActive)
        updateModifierButtons()
    }

    private fun consumeModifiers() {
        if (!ctrlActive && !altActive && !shiftActive) return
        ctrlActive = false
        altActive = false
        shiftActive = false
        terminalBackend?.setCtrl(false)
        terminalBackend?.setAlt(false)
        terminalBackend?.setShift(false)
        updateModifierButtons()
    }

    private fun updateModifierButtons() {
        for (row in listOf(row1Container, row2Container)) {
            val r = row ?: continue
            for (i in 0 until r.childCount) {
                val btn = r.getChildAt(i) as? Button ?: continue
                when (btn.text) {
                    "CTRL" -> btn.setBackgroundColor(if (ctrlActive) modifierHighlightColor() else 0)
                    "ALT" -> btn.setBackgroundColor(if (altActive) modifierHighlightColor() else 0)
                    "SHIFT" -> btn.setBackgroundColor(if (shiftActive) modifierHighlightColor() else 0)
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

    internal fun sendInputLine(text: String) {
        val s = session ?: return
        s.write(if (text.isEmpty()) "\r" else text + "\r")
    }

    private val session: TerminalSession?
        get() = if (currentIndex in sessions.indices) sessions[currentIndex] else null

    private fun requestNewSession() {
        if (!installer.getRootfsDir(distroName).exists()) {
            ensureDistroInstalled()
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, TerminalService::class.java).apply {
                action = TerminalService.ACTION_CREATE_SESSION
            },
        )
    }

    private fun switchToSession(index: Int) {
        if (index !in sessions.indices || index == currentIndex) return
        val session = sessions[index]
        sessionStore.switchToSession(session)
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
        updateDrawer()
    }
    private fun handleSessionFinished(finishedSession: TerminalSession) {
        // Navigation on the last session finishing is handled by the
        // observeSessions() collector (the store update races with this
        // callback), so only the view attachment is dealt with here.
        if (sessions.isNotEmpty()) {
            if (terminalView.mTermSession === finishedSession) {
                val safeIdx = currentIndex.coerceIn(0, sessions.lastIndex)
                terminalView.attachSession(sessions[safeIdx])
                terminalView.onScreenUpdated()
            }
            updateDrawer()
        }
        RedTWidgetProvider.updateAll(this)
    }

    private fun exitApp() {
        // The last session ended (exit typed or drawer close): RedT has no
        // hub screen anymore, so close the whole task including Settings
        // stacked on top of the terminal.
        if (!isFinishing && !isDestroyed) {
            finishAffinity()
        }
    }

    private fun closeSession(index: Int) {
        // Closing the last session is allowed: the store becomes empty and
        // observeSessions() closes the app, exactly like typing `exit` in
        // the shell.
        sessionStore.removeSession(index)
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
        // Snapshot the transcript on the UI thread (the emulator's screen buffer
        // is mutated by the terminal renderer / reader thread) so the background
        // job does not race with concurrent writes.
        val snapshot = try {
            s.emulator.getScreen().getTranscriptText()
        } catch (_: Exception) {
            Toast.makeText(this, "Export failed: nothing to export", Toast.LENGTH_SHORT).show()
            return
        }
        val distro = distroName
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var dir = File(
                    android.os.Environment.getExternalStorageDirectory(), "RedT/exports"
                )
                dir.mkdirs()
                if (!dir.exists()) dir = File(filesDir, "exports").apply { mkdirs() }
                val f = File(dir, "$distro-${System.currentTimeMillis()}.txt")
                f.writeText(snapshot)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this@TerminalActivity, "Exported: ${f.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this@TerminalActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
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

    private var errorOverlay: TextView? = null

    private fun clearErrorOverlay() {
        errorOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        errorOverlay = null
        terminalView.visibility = android.view.View.VISIBLE
    }

    private var installOverlay: LinearLayout? = null
    private var installProgressBar: ProgressBar? = null
    private var installStatusText: TextView? = null
    private var installJob: Job? = null

    /**
     * The app opens straight into the terminal: a missing distro rootfs is
     * downloaded and extracted here before the first session is created.
     */
    private fun ensureDistroInstalled() {
        if (installJob?.isActive == true) return
        val distro = DistroRegistry.alpine
        showInstallOverlay(distro.displayName)
        installJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(ProotInstaller.isInstalled(applicationContext)) {
                        getString(R.string.proot_extraction_failed)
                    }
                    installer.install(distro) { progress ->
                        runOnUiThread { renderInstallProgress(progress) }
                    }
                }
                clearInstallOverlay()
                updateDistroSizeLabel()
                Toast.makeText(this@TerminalActivity, "${distro.displayName} installed", Toast.LENGTH_SHORT).show()
                requestNewSession()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: DistroInstaller.CancelledException) {
                showInstallFailed("Installation cancelled")
            } catch (e: Exception) {
                showInstallFailed(e.message ?: "Unknown error")
            }
        }
    }

    private fun showInstallOverlay(displayName: String) {
        clearErrorOverlay()
        clearInstallOverlay()
        terminalView.visibility = android.view.View.GONE
        val parent = terminalView.parent as? ViewGroup ?: return
        val textColor = themeColor(R.attr.terminalText, 0xFFCDD6F4.toInt())
        val status = TextView(this).apply {
            text = "Preparing..."
            setTextColor(textColor)
            textSize = 14f
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(this@TerminalActivity).apply {
                text = "Installing $displayName..."
                setTextColor(textColor)
                textSize = 16f
                setPadding(0, 0, 0, dp(12))
            })
            addView(bar)
            addView(status.apply { setPadding(0, dp(8), 0, 0) })
        }
        installProgressBar = bar
        installStatusText = status
        installOverlay = box
        parent.addView(box)
    }

    private fun renderInstallProgress(progress: DistroInstaller.Progress) {
        val bar = installProgressBar ?: return
        val text = installStatusText ?: return
        if (progress.percent < 0) {
            bar.isIndeterminate = true
            text.text = progress.speed
        } else {
            bar.isIndeterminate = false
            bar.progress = progress.percent
            text.text = "${progress.percent}% - ${progress.speed}"
        }
    }

    private fun clearInstallOverlay() {
        installOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        installOverlay = null
        installProgressBar = null
        installStatusText = null
        terminalView.visibility = android.view.View.VISIBLE
    }

    private fun showInstallFailed(message: String) {
        val text = installStatusText ?: return
        text.text = "Install failed:\n$message"
        text.setTextColor(0xFFFF6B6B.toInt())
        installProgressBar?.visibility = android.view.View.GONE
        val overlay = installOverlay ?: return
        if (overlay.getChildAt(overlay.childCount - 1) !is Button) {
            overlay.addView(Button(this).apply {
                text = "Retry"
                setOnClickListener { ensureDistroInstalled() }
            })
        }
    }


    private fun startForegroundService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, TerminalService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("TerminalActivity", "Foreground service failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        syncExtraKeysRows()
        sendServiceAction(TerminalService.ACTION_AUTO_RELEASE)
        if (!com.redt.util.StoragePermission.isAccessible()) {
            val prefs = prefs()
            val lastAsk = prefs.getLong("storage_ask_time", 0L)
            if (System.currentTimeMillis() - lastAsk > Prefs.PERMISSION_ASK_THROTTLE_MS) {
                prefs.edit().putLong("storage_ask_time", System.currentTimeMillis()).apply()
                com.redt.util.StoragePermission.requestAccess(this)
            }
        }
        terminalView.requestFocus()
        terminalView.onScreenUpdated()
        applyTerminalColors()
        terminalBackend?.applyFontSize()
        syncWakeLock()
        updateModifierButtons()
        updateExtraKeysVisibility()
        RedTWidgetProvider.updateAll(this)
    }

    override fun onPause() {
        super.onPause()
        // Packages installed inside the guest change the rootfs while this
        // screen is open; drop the size cache so the next label recomputes
        // instead of showing a value up to the cache TTL stale.
        com.redt.util.Format.invalidate(installer.getRootfsDir(distroName))
        if (sessions.isNotEmpty()) {
            sendServiceAction(TerminalService.ACTION_AUTO_WAKE)
        }
    }

    private fun sendServiceAction(action: String) {
        try {
            startService(Intent(this, TerminalService::class.java).apply { this.action = action })
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        RedTWidgetProvider.updateAll(this)
        if (installJob?.isActive == true) {
            installer.cancel()
            installJob?.cancel()
        }
        terminalBackend?.let {
            sessionStore.detachClient(it)
            it.onSessionFinished = null
            it.onModifierConsumed = null
            it.onEmulatorReady = null
        }
        terminalBackend = null
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN && ev.y < dp(40) && ev.rawY < dp(120)) {
            val prefs = prefs()
            if (prefs.getBoolean("autohide_keys", false)) {
                updateExtraKeysVisibility()
            }
        }
        return super.dispatchTouchEvent(ev)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentFocus is android.widget.EditText) return super.dispatchKeyEvent(event)
        if (currentIndex !in sessions.indices) return super.dispatchKeyEvent(event)
        val hadModifier = ctrlActive || altActive || shiftActive
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
        menu.add(0, 15, 0, "Settings")
    }

    private fun resolveTerminalColors(): Triple<Int, Int, Int> = Triple(
        themeColor(R.attr.terminalBg, 0xFF1E1E2E.toInt()),
        themeColor(R.attr.extraKeysBg, 0xFF181825.toInt()),
        themeColor(R.attr.terminalText, 0xFFCDD6F4.toInt())
    )

    private fun applyEmulatorColors(view: TerminalView) {
        val emulator = view.mEmulator ?: return
        val (bg, _, textColor) = resolveTerminalColors()
        val palette = emulator.mColors.mCurrentColors
        palette[TextStyle.COLOR_INDEX_FOREGROUND] = textColor
        palette[TextStyle.COLOR_INDEX_BACKGROUND] = bg
        palette[TextStyle.COLOR_INDEX_CURSOR] = textColor
        view.invalidate()
    }

    private fun applyTerminalColors() {
        val (bg, extraBg, textColor) = resolveTerminalColors()
        terminalView.setBackgroundColor(bg)
        drawerLayout.setBackgroundColor(bg)
        extraKeysWrapper.setBackgroundColor(extraBg)
        terminalBgLayer?.setBackgroundColor(bg)
        rootContainer?.setBackgroundColor(bg)
        for (row in listOf(row1Container, row2Container)) {
            val r = row ?: continue
            for (i in 0 until r.childCount) (r.getChildAt(i) as? android.widget.TextView)?.setTextColor(textColor)
        }
        inputField?.apply {
            setTextColor(textColor)
            setHintTextColor(hintColor())
        }
        applyEmulatorColors(terminalView)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            12 -> { copySelectedText(); true }
            13 -> { pasteClipboard(); true }
            14 -> { exportCurrentOutput(); true }
            15 -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            else -> super.onContextItemSelected(item)
        }
    }


    private fun syncWakeLock() {
        val enabled = prefs().getBoolean(Prefs.KEY_WAKELOCK, Prefs.WAKELOCK_DEFAULT)
        if (enabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

}
