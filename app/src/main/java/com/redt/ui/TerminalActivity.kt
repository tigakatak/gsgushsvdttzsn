package com.redt.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ContextMenu
import android.view.Gravity
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.redt.session.terminalSessionStore
import com.redt.service.TerminalService
import com.redt.util.Format
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalActivity : AppCompatActivity() {

    private lateinit var distroName: String
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

    internal fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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

    private val sessionStore by lazy { terminalSessionStore }
    private val sessions: List<TerminalSession> get() = sessionStore.sessions.value
    private val currentIndex: Int get() = sessionStore.currentIndex.value

    private var lastAppliedTheme: String? = null

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

        distroName = intent?.getStringExtra(EXTRA_DISTRO) ?: "alpine"
        prefs()
            .edit().putString(Prefs.KEY_LAST_DISTRO, distroName).apply()
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
            requestNewSession(distroName)
        }

        observeSessions()
        if (sessions.isEmpty()) {
            requestNewSession(distroName)
        } else {
            startForegroundService()
            val backend = ensureTerminalBackend()
            for (s in sessions) {
                sessionStore.attachClient(s, backend)
            }
            backend.applyFontSize()
            applyFontFromPrefs(prefs)
            terminalView.setBackgroundColor(terminalBgWithAlpha())
            val target = sessionStore.indexOfSessionForDistro(distroName)
            if (target >= 0) {
                sessionStore.switchToSession(sessions[target])
                terminalView.attachSession(sessions[target])
                terminalView.onScreenUpdated()
                terminalView.post {
                    terminalView.requestFocus()
                    terminalView.isFocusableInTouchMode = true
                }
            } else {
                requestNewSession(distroName)
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

    private fun observeSessions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(sessionStore.sessions, sessionStore.currentIndex) { active, index ->
                    active to index
                }.collect { (active, index) ->
                    val backend = ensureTerminalBackend()
                    if (terminalView.mRenderer == null) {
                        backend.applyFontSize()
                        applyFontFromPrefs(prefs())
                        terminalView.setBackgroundColor(terminalBgWithAlpha())
                    }
                    active.forEach { sessionStore.attachClient(it, backend) }
                    if (active.isEmpty()) {
                        updateDrawer()
                        return@collect
                    }
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
        val displayedDistro = distroName
        val rootfsDir = DistroInstaller(applicationContext).getRootfsDir(displayedDistro)
        val sizeLabel = findViewById<TextView>(R.id.distro_size_label)
        val cached = Format.cachedSize(rootfsDir)
        sizeLabel.text = if (cached != null) {
            "$displayedDistro (${Format.size(cached)})"
        } else {
            displayedDistro
        }
        val weakLabel = java.lang.ref.WeakReference(sizeLabel)
        Format.dirSizeAsync(rootfsDir) { bytes ->
            if (distroName != displayedDistro) return@dirSizeAsync
            val view = weakLabel.get() ?: return@dirSizeAsync
            if (view.isAttachedToWindow) {
                view.text = "$displayedDistro (${Format.size(bytes)})"
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
            "TAB" to { session?.writeCodePoint(false, 9); Unit },
            "CTRL" to { toggleCtrl() },
            "ALT" to { toggleAlt() },
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
        if (label == "CTRL" || label == "ALT") return action
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
        for (row in listOf(row1Container, row2Container)) {
            val r = row ?: continue
            for (i in 0 until r.childCount) {
                val btn = r.getChildAt(i) as? Button ?: continue
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

    internal fun sendInputLine(text: String) {
        val s = session ?: return
        s.write(if (text.isEmpty()) "\r" else text + "\r")
    }

    private val session: TerminalSession?
        get() = if (currentIndex in sessions.indices) sessions[currentIndex] else null

    private fun requestNewSession(requestedDistro: String) {
        val rootfsDir = DistroInstaller(applicationContext).getRootfsDir(requestedDistro)
        if (!rootfsDir.exists()) {
            showError("Distro $requestedDistro not installed.\nRun installer first.")
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, TerminalService::class.java).apply {
                action = TerminalService.ACTION_CREATE_SESSION
                putExtra(TerminalService.EXTRA_DISTRO, requestedDistro)
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
        if (sessions.isEmpty()) {
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
                finish()
            }
        } else {
            if (terminalView.mTermSession === finishedSession) {
                val safeIdx = currentIndex.coerceIn(0, sessions.lastIndex)
                terminalView.attachSession(sessions[safeIdx])
                terminalView.onScreenUpdated()
            }
            updateDrawer()
        }
        RedTWidgetProvider.updateAll(this)
    }

    private fun closeSession(index: Int) {
        if (sessions.size <= 1) return
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

    private fun showError(msg: String) {
        terminalView.visibility = android.view.View.GONE
        val parent = terminalView.parent as? android.view.ViewGroup ?: return
        val tv = TextView(this).apply {
            text = msg
            setTextColor(themeColor(R.attr.terminalText, 0xFFCDD6F4.toInt()))
            textSize = 14f
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        parent.addView(tv)
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
        val currentTheme = prefs().getString(Prefs.KEY_THEME, "amoled")
        if (lastAppliedTheme != null && lastAppliedTheme != currentTheme) {
            applyTheme()
            applyTerminalColors()
        }
        lastAppliedTheme = currentTheme
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
        syncFontFromPrefs()
        syncWakeLock()
        updateModifierButtons()
        updateExtraKeysVisibility()
        RedTWidgetProvider.updateAll(this)
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
        val newDistro = intent.getStringExtra(EXTRA_DISTRO) ?: return
        distroName = newDistro
        prefs().edit().putString(Prefs.KEY_LAST_DISTRO, distroName).apply()
        updateDistroSizeLabel()

        val target = sessionStore.indexOfSessionForDistro(newDistro)
        if (target >= 0) {
            sessionStore.switchToSession(sessions[target])
            terminalView.attachSession(sessions[target])
            terminalView.onScreenUpdated()
            terminalView.requestFocus()
            updateDrawer()
        } else {
            requestNewSession(newDistro)
        }
    }

    override fun onDestroy() {
        RedTWidgetProvider.updateAll(this)
        terminalBackend?.let {
            sessionStore.detachClient(it)
            it.onSessionFinished = null
            it.onLinkTap = null
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
        menu.add(0, 9, 0, "Snippets")
    }

    private fun resolveTerminalColors(): Triple<Int, Int, Int> {
        val prefs = prefs()
        val themeName = prefs.getString("theme", "amoled") ?: "amoled"
        val bg = if (themeName == "custom")
            prefs.getInt("custom_bg", 0xFF1E1E2E.toInt())
        else
            AppTheme.resolveThemeColor(this, prefs, R.attr.terminalBg, 0xFF1E1E2E.toInt())
        val extraBg = if (themeName == "custom")
            prefs.getInt("custom_bg", 0xFF0A0A0A.toInt())
        else
            AppTheme.resolveThemeColor(this, prefs, R.attr.extraKeysBg, 0xFF181825.toInt())
        val textColor = if (themeName == "custom")
            prefs.getInt("custom_text", 0xFFCDD6F4.toInt())
        else
            AppTheme.resolveThemeColor(this, prefs, R.attr.terminalText, 0xFFCDD6F4.toInt())
        return Triple(bg, extraBg, textColor)
    }

    private fun colorWithAlpha(color: Int): Int {
        val opacity = prefs().getInt("terminal_opacity", 10).coerceIn(0, 10)
        val alpha = (opacity * 25.5).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun terminalBgWithAlpha(): Int = colorWithAlpha(resolveTerminalColors().first)

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
        lastAppliedTheme = themeName
        applyTheme()
        applyTerminalColors()
    }

    private fun applyTerminalColors() {
        val (bg, extraBg, textColor) = resolveTerminalColors()
        val bgWithAlpha = colorWithAlpha(bg)
        val extraBgWithAlpha = colorWithAlpha(extraBg)
        terminalView.setBackgroundColor(bgWithAlpha)
        drawerLayout.setBackgroundColor(bg)
        extraKeysWrapper.setBackgroundColor(extraBgWithAlpha)
        terminalBgLayer?.setBackgroundColor(bg)
        rootContainer?.setBackgroundColor(bg)
        for (row in listOf(row1Container, row2Container)) {
            val r = row ?: continue
            for (i in 0 until r.childCount) (r.getChildAt(i) as? android.widget.TextView)?.setTextColor(textColor)
        }
        inputField?.apply {
            setTextColor(textColor)
            setHintTextColor(textColor)
        }
        applyEmulatorColors(terminalView)
    }

    private fun resetTerminalDefaults(prefs: android.content.SharedPreferences) {
        session?.reset()
        prefs.edit().putString("font", "monospace").apply()
        applyFontFromPrefs(prefs)
        terminalBackend?.setFontSize(Prefs.FONT_SIZE_DEFAULT)
        applyTerminalTheme("amoled")
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val prefs = prefs()
        return when (item.itemId) {
            12 -> { copySelectedText(); true }
            13 -> { pasteClipboard(); true }
            14 -> { exportCurrentOutput(); true }
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

    private fun syncFontFromPrefs() {
        val backend = terminalBackend ?: return
        backend.applyFontSize()
        applyFontFromPrefs(prefs())
    }

    private fun syncWakeLock() {
        val enabled = prefs().getBoolean(Prefs.KEY_WAKELOCK, Prefs.WAKELOCK_DEFAULT)
        if (enabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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

    private fun applyTheme() {
        AppTheme.apply(this)
        lastAppliedTheme = prefs().getString(Prefs.KEY_THEME, "amoled")
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
                session.write(content)
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

        android.app.AlertDialog.Builder(this)
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
