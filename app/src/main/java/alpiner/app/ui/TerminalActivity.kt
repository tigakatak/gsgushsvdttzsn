package alpiner.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextMenu
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import alpiner.app.R
import alpiner.app.distro.AlpineInstaller
import alpiner.app.distro.AlpineRegistry
import alpiner.app.proot.ProotInstaller
import alpiner.app.session.sessionStore
import alpiner.app.service.TerminalService
import alpiner.app.util.Clipboard
import alpiner.app.util.StoragePermission
import com.google.android.material.card.MaterialCardView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalActivity : AppCompatActivity() {

    private val distro = AlpineRegistry.alpine
    private lateinit var terminalView: TerminalView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sessionListContainer: LinearLayout
    private lateinit var extraKeysWrapper: LinearLayout
    private lateinit var extraKeysPager: ViewPager2
    private var rootContainer: LinearLayout? = null
    private var terminalBgLayer: View? = null
    private var row1Container: LinearLayout? = null
    private var row2Container: LinearLayout? = null
    private var inputField: EditText? = null

    private val modifiers = ModifierState()
    private var terminalBackend: TerminalBackend? = null
    private val installer by lazy { AlpineInstaller(applicationContext) }

    internal fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MENU_COPY = 12
        private const val MENU_PASTE = 13
        private const val MENU_EXPORT = 14
        private const val MENU_SETTINGS = 15

        private val MODIFIER_KEY_CODES: Set<Int> =
            TerminalModifier.entries.flatMapTo(mutableSetOf()) { it.keyCodes }

        fun launchIntent(context: Context): Intent =
            Intent(context, TerminalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        fun launch(context: Context) {
            context.startActivity(launchIntent(context))
        }
    }

    private val sessions: List<TerminalSession> get() = sessionStore.sessions
    private val currentIndex: Int get() = sessionStore.currentIndex


    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("Notifications will be suppressed; the terminal service will still run")
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

        if (!StoragePermission.isAccessible()) {
            toast("Alpiner needs All files access to use /storage/emulated/0 in the terminal")
            StoragePermission.requestAccess(this)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                val labels = extraKeyLabels()
                setupExtraKeysRow(row1Container, labels.first)
                setupExtraKeysRow(row2Container, labels.second)
                updateModifierButtons()
            },
            onInputPageReady = { et -> inputField = et }
        )

        drawerLayout.setDrawerLockMode(
            DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            GravityCompat.START
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

        if (prefs.getBoolean(Prefs.KEY_WAKELOCK, Prefs.WAKELOCK_DEFAULT)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (prefs.getBoolean(Prefs.KEY_AUTOHIDE_KEYS, false)) {
            toggleExtraKeys(false)
        }

        findViewById<TextView>(R.id.new_session_button).setOnClickListener {
            requestNewSession()
        }

        observeSessions()
        lifecycleScope.launch {
            sessionStore.exitSignal.collect {
                // Exit from the notification means "quit Alpiner entirely":
                // close every activity in the task, not just this screen.
                exitingApp = true
                finishAffinity()
            }
        }
        if (sessions.isEmpty()) {
            requestNewSession()
        } else {
            // observeSessions() attaches the backend, the current session
            // and the drawer on its first collection (Lifecycle.STARTED).
            startForegroundService()
        }
    }

    private fun ensureTerminalBackend(): TerminalBackend =
        terminalBackend ?: TerminalBackend(terminalView, this, modifiers).also {
            terminalBackend = it
            terminalView.setTerminalViewClient(it)
            wireBackend(it)
        }

    private var exitingApp = false
    private var sawActiveSession = false

    private fun observeSessions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionStore.state.collect { st ->
                    val active = st.sessions
                    val backend = ensureTerminalBackend()
                    // mRenderer is only set once the view has been laid out,
                    // so a null renderer means "first emission, not yet
                    // initialized" and the font/colors must be (re)applied.
                    if (terminalView.mRenderer == null) {
                        backend.applyFontSize()
                        terminalView.setBackgroundColor(terminalBgColor())
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
                    // The store updates the list and index atomically, but a
                    // fresh session can briefly appear before its index is
                    // switched to, so clamp defensively.
                    val current = active[st.currentIndex.coerceIn(active.indices)]
                    if (terminalView.mTermSession !== current) {
                        showSession(current)
                    }
                    updateDrawer()
                }
            }
        }
    }

    private fun isRepeatableKey(label: String): Boolean =
        specByLabel[label]?.repeatable == true

    /** Shared handler for every repeatable extra key button. */
    private val keyRepeatHandler = Handler(Looper.getMainLooper())

    private fun createKeyButton(label: String, action: () -> Unit): Button {
        val textColor = terminalTextColor()
        val repeatable = isRepeatableKey(label)
        val isSymbol = label.length == 1 && !label[0].isLetterOrDigit()
        val initialDelay = Prefs.KEY_REPEAT_INITIAL_DELAY
        val repeatDelay = Prefs.KEY_REPEAT_DELAY
        val repeatRunnable = object : Runnable {
            override fun run() {
                action()
                keyRepeatHandler.postDelayed(this, repeatDelay)
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
                    MotionEvent.ACTION_DOWN -> {
                        setBackgroundColor(modifierHighlightColor())
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (repeatable) {
                            action()
                            keyRepeatHandler.postDelayed(repeatRunnable, initialDelay)
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        setBackgroundColor(0)
                        keyRepeatHandler.removeCallbacks(repeatRunnable)
                        repeatable
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
        val split = { s: String -> s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
        return split(prefs.getString(Prefs.KEY_EXTRA_KEYS_ROW1, Prefs.EXTRA_KEYS_ROW1_DEFAULT)!!) to
            split(prefs.getString(Prefs.KEY_EXTRA_KEYS_ROW2, Prefs.EXTRA_KEYS_ROW2_DEFAULT)!!)
    }

    /**
     * Single table of every known extra key: the labels that select it
     * (glyph or word), whether holding it auto-repeats, whether it needs a
     * live session, and what it does. Unknown labels fall back to typing
     * their text into the session.
     */
    private class ExtraKeySpec(
        val labels: Set<String>,
        val repeatable: Boolean = false,
        val requiresSession: Boolean = true,
        val modifier: TerminalModifier? = null,
        val action: TerminalActivity.() -> Unit,
    )

    private val specByLabel: Map<String, ExtraKeySpec> = listOf(
        ExtraKeySpec(setOf("\u2630", "MENU"), requiresSession = false) { toggleSessionsPanel() },
        ExtraKeySpec(setOf("ESC")) { session?.writeCodePoint(false, 27) },
        ExtraKeySpec(setOf("TAB")) { key(KeyEvent.KEYCODE_TAB) },
        ExtraKeySpec(setOf("CTRL"), requiresSession = false, modifier = TerminalModifier.CTRL) {
            toggleModifier(TerminalModifier.CTRL)
        },
        ExtraKeySpec(setOf("ALT"), requiresSession = false, modifier = TerminalModifier.ALT) {
            toggleModifier(TerminalModifier.ALT)
        },
        ExtraKeySpec(setOf("SHIFT"), requiresSession = false, modifier = TerminalModifier.SHIFT) {
            toggleModifier(TerminalModifier.SHIFT)
        },
        ExtraKeySpec(setOf("\u25B2", "UP"), repeatable = true) { key(KeyEvent.KEYCODE_DPAD_UP) },
        ExtraKeySpec(setOf("\u25BC", "DOWN"), repeatable = true) { key(KeyEvent.KEYCODE_DPAD_DOWN) },
        ExtraKeySpec(setOf("\u25C0", "LEFT"), repeatable = true) { key(KeyEvent.KEYCODE_DPAD_LEFT) },
        ExtraKeySpec(setOf("\u25B6", "RIGHT"), repeatable = true) { key(KeyEvent.KEYCODE_DPAD_RIGHT) },
        ExtraKeySpec(setOf("HOME"), repeatable = true) { key(KeyEvent.KEYCODE_MOVE_HOME) },
        ExtraKeySpec(setOf("END"), repeatable = true) { key(KeyEvent.KEYCODE_MOVE_END) },
        ExtraKeySpec(setOf("INS"), repeatable = true) { key(KeyEvent.KEYCODE_INSERT) },
        ExtraKeySpec(setOf("DEL"), repeatable = true) { key(KeyEvent.KEYCODE_FORWARD_DEL) },
        ExtraKeySpec(setOf("\u232B", "BACKSPACE"), repeatable = true) { key(KeyEvent.KEYCODE_DEL) },
        // The em-dash key intentionally writes a literal "-".
        ExtraKeySpec(setOf("\u2014")) { session?.write("-") },
    ).flatMap { spec -> spec.labels.map { it to spec } }.toMap()

    /** Sends [keyCode] with the currently active sticky modifiers. */
    private fun key(keyCode: Int) {
        terminalView.handleKeyCode(keyCode, modifiers.keyMod())
    }

    /** Runs the extra key selected by [label]; see [ExtraKeySpec]. */
    private fun performKeyAction(label: String) {
        val spec = specByLabel[label]
        if (spec?.requiresSession != false && session == null) return
        if (spec != null) spec.action(this) else session?.write(label)
        if (spec?.modifier == null) consumeModifiers()
    }

    private fun setupExtraKeysRow(container: LinearLayout?, labels: List<String>) {
        container ?: return
        for (label in labels) {
            container.addView(createKeyButton(label) { performKeyAction(label) })
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
        setupExtraKeysRow(row1Container, labels.first)
        setupExtraKeysRow(row2Container, labels.second)
        updateModifierButtons()
    }

    private var lastImeVisible = false

    private fun toggleModifier(modifier: TerminalModifier) {
        modifiers.toggle(modifier)
        updateModifierButtons()
    }

    private fun consumeModifiers() {
        if (!modifiers.any()) return
        modifiers.clear()
        updateModifierButtons()
    }

    private fun updateModifierButtons() {
        forEachKeyButton { btn ->
            val modifier = TerminalModifier.forLabel(btn.text.toString()) ?: return@forEachKeyButton
            btn.setBackgroundColor(
                if (modifiers.isActive(modifier)) modifierHighlightColor() else 0
            )
        }
    }

    /** Visits every key button currently mounted in the two extra-keys rows. */
    private fun forEachKeyButton(action: (Button) -> Unit) {
        for (row in listOf(row1Container, row2Container)) {
            val r = row ?: continue
            for (i in 0 until r.childCount) {
                val btn = r.getChildAt(i) as? Button ?: continue
                action(btn)
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
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) hideSessionsPanel()
        else drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun hideSessionsPanel() {
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun toggleExtraKeys(show: Boolean) {
        extraKeysWrapper.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateExtraKeysVisibility() {
        val prefs = prefs()
        toggleExtraKeys(
            if (prefs.getBoolean(Prefs.KEY_AUTOHIDE_KEYS, false)) lastImeVisible else true
        )
    }

    internal fun sendInputLine(text: String) {
        val s = session ?: return
        s.write(if (text.isEmpty()) "\r" else text + "\r")
    }

    private val session: TerminalSession?
        get() = if (currentIndex in sessions.indices) sessions[currentIndex] else null

    /** Attaches [session] to the terminal view and requests a redraw. */
    private fun showSession(session: TerminalSession) {
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
    }

    private fun requestNewSession() {
        if (!installer.isInstalled(distro.name)) {
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
        sessionStore.switchToSession(sessions[index])
    }

    private fun handleSessionFinished(finishedSession: TerminalSession) {
        // Navigation on the last session finishing is handled by the
        // observeSessions() collector (the store update races with this
        // callback), so only the view attachment is dealt with here.
        if (sessions.isEmpty()) return
        if (terminalView.mTermSession === finishedSession) {
            showSession(sessions[currentIndex.coerceIn(0, sessions.lastIndex)])
        }
        updateDrawer()
    }


    private fun exitApp() {
        // The last session ended: Alpiner has no hub screen anymore, so close
        // the whole task including Settings stacked on top of the terminal.
        if (!isFinishing && !isDestroyed) finishAffinity()
    }

    private fun closeSession(index: Int) {
        // Closing the last session is allowed: the store becomes empty and
        // observeSessions() closes the app, exactly like typing `exit` in
        // the shell. View updates flow from the state collector.
        sessionStore.removeSession(index)
    }

    private fun updateDrawer() {
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
        sessions.indices.forEach { sessionListContainer.addView(buildSessionCard(it)) }
    }

    private fun buildSessionCard(i: Int): MaterialCardView {
        val bgColor = if (i == currentIndex) extraKeysBgColor() else 0
        return MaterialCardView(this).apply {
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
                showRenameSessionDialog(i)
                true
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 10, 8, 10)
                addView(TextView(context).apply {
                    text = sessions[i].mSessionName
                    setTextColor(terminalTextColor())
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    isClickable = false
                    isFocusable = false
                    isLongClickable = false
                })
                val dotSize = dp(12)
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                        gravity = Gravity.CENTER
                        setMargins(0, 0, dp(12), 0)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
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
                        AppCompatResources.getDrawable(
                            context, android.R.drawable.ic_menu_close_clear_cancel
                        )
                    )
                    imageTintList = ColorStateList.valueOf(mutedTextColor())
                    setOnClickListener { closeSession(i) }
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                })
            })
        }
    }

    private fun showRenameSessionDialog(index: Int) {
        val currentLabel = sessions[index].mSessionName
        val input = EditText(this).apply { setText(currentLabel) }
        AlertDialog.Builder(this)
            .setTitle("Rename session")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    sessions[index].mSessionName = newName
                    updateDrawer()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportCurrentOutput() {
        val s = session ?: return
        hideSessionsPanel()
        // Snapshot the transcript on the UI thread (the emulator's screen
        // buffer is mutated by the renderer/reader thread) so the background
        // job does not race with concurrent writes.
        val snapshot = try {
            s.emulator.getScreen().getTranscriptText()
        } catch (_: Exception) {
            toast("Export failed: nothing to export", Toast.LENGTH_SHORT)
            return
        }
        val name = distro.name
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var dir = File(
                    Environment.getExternalStorageDirectory(), "Alpiner/exports"
                )
                dir.mkdirs()
                if (!dir.exists()) dir = File(filesDir, "exports").apply { mkdirs() }
                val f = File(dir, "$name-${System.currentTimeMillis()}.txt")
                f.writeText(snapshot)
                withContext(Dispatchers.Main) {
                    toast("Exported: ${f.absolutePath}", onlyIfAlive = true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("Export failed: ${e.message}", onlyIfAlive = true)
                }
            }
        }
    }

    /** Shows a toast; pass [onlyIfAlive] from background coroutines to skip dead activities. */
    private fun toast(message: String, duration: Int = Toast.LENGTH_LONG, onlyIfAlive: Boolean = false) {
        if (onlyIfAlive && (isFinishing || isDestroyed)) return
        Toast.makeText(this, message, duration).show()
    }

    private fun copySelectedText() {
        if (terminalView.isSelectingText) {
            val text = terminalView.getSelectedText()
            if (!text.isNullOrEmpty()) {
                Clipboard.copy(this, text)
                terminalView.stopTextSelectionMode()
                toast("Copied ${text.length} chars", Toast.LENGTH_SHORT)
                return
            }
        }
        session?.let {
            val text = it.emulator.getScreen().getTranscriptText()
            Clipboard.copy(this, text)
            toast("Copied entire output (${text.length} chars)", Toast.LENGTH_SHORT)
        }
    }

    private fun pasteClipboard() {
        val text = Clipboard.primaryText(this) ?: return
        terminalView.mEmulator?.paste(text)
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
                toast("${distro.displayName} installed", Toast.LENGTH_SHORT)
                requestNewSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AlpineInstaller.CancelledException) {
                showInstallFailed("Installation cancelled")
            } catch (e: Exception) {
                showInstallFailed(e.message ?: "Unknown error")
            }
        }
    }

    private fun showInstallOverlay(displayName: String) {
        clearInstallOverlay()
        terminalView.visibility = View.GONE
        val parent = terminalView.parent as? ViewGroup ?: return
        val textColor = terminalTextColor()
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

    private fun renderInstallProgress(progress: AlpineInstaller.Progress) {
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
        terminalView.visibility = View.VISIBLE
    }

    private fun showInstallFailed(message: String) {
        val status = installStatusText ?: return
        status.text = "Install failed:\n$message"
        status.setTextColor(0xFFFF6B6B.toInt())
        installProgressBar?.visibility = View.GONE
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
            Log.e("TerminalActivity", "Foreground service failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        syncExtraKeysRows()
        if (!StoragePermission.isAccessible()) {
            val prefs = prefs()
            val lastAsk = prefs.getLong(Prefs.KEY_STORAGE_ASK_TIME, 0L)
            if (System.currentTimeMillis() - lastAsk > Prefs.PERMISSION_ASK_THROTTLE_MS) {
                prefs.edit().putLong(Prefs.KEY_STORAGE_ASK_TIME, System.currentTimeMillis()).apply()
                StoragePermission.requestAccess(this)
            }
        }
        terminalView.requestFocus()
        terminalView.onScreenUpdated()
        applyTerminalColors()
        terminalBackend?.applyFontSize()
        syncWakeLock()
        updateModifierButtons()
        updateExtraKeysVisibility()
    }

    override fun onDestroy() {
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Heuristic top-edge band: taps this close to the window's top (and
        // the physical screen's) can follow a system-UI gesture that changed
        // IME state, so in auto-hide mode re-evaluate extra-keys visibility
        // before the tap lands.
        if (ev.action == MotionEvent.ACTION_DOWN &&
            ev.y < dp(40) && ev.rawY < dp(120) &&
            prefs().getBoolean(Prefs.KEY_AUTOHIDE_KEYS, false)
        ) {
            updateExtraKeysVisibility()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentFocus is EditText) return super.dispatchKeyEvent(event)
        if (currentIndex !in sessions.indices) return super.dispatchKeyEvent(event)
        val hadModifier = modifiers.any()
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
            event.keyCode !in MODIFIER_KEY_CODES
        ) {
            consumeModifiers()
        }
        return handled
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        menu.add(0, MENU_COPY, 0, "Copy")
        menu.add(0, MENU_PASTE, 0, "Paste")
        menu.add(0, MENU_EXPORT, 0, "Export")
        menu.add(0, MENU_SETTINGS, 0, "Settings")
    }

    private fun applyEmulatorColors(view: TerminalView) {
        val emulator = view.mEmulator ?: return
        val textColor = terminalTextColor()
        val palette = emulator.mColors.mCurrentColors
        palette[TextStyle.COLOR_INDEX_FOREGROUND] = textColor
        palette[TextStyle.COLOR_INDEX_BACKGROUND] = terminalBgColor()
        palette[TextStyle.COLOR_INDEX_CURSOR] = textColor
        view.invalidate()
    }

    private fun applyTerminalColors() {
        val bg = terminalBgColor()
        val extraBg = extraKeysBgColor()
        val textColor = terminalTextColor()
        terminalView.setBackgroundColor(bg)
        drawerLayout.setBackgroundColor(bg)
        extraKeysWrapper.setBackgroundColor(extraBg)
        terminalBgLayer?.setBackgroundColor(bg)
        rootContainer?.setBackgroundColor(bg)
        forEachKeyButton { it.setTextColor(textColor) }
        inputField?.apply {
            setTextColor(textColor)
            setHintTextColor(hintColor())
        }
        applyEmulatorColors(terminalView)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_COPY -> { copySelectedText(); true }
            MENU_PASTE -> { pasteClipboard(); true }
            MENU_EXPORT -> { exportCurrentOutput(); true }
            MENU_SETTINGS -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            else -> super.onContextItemSelected(item)
        }
    }


    private fun syncWakeLock() {
        val keepScreenOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (prefs().getBoolean(Prefs.KEY_WAKELOCK, Prefs.WAKELOCK_DEFAULT)) window.addFlags(keepScreenOn)
        else window.clearFlags(keepScreenOn)
    }

}
