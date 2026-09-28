package alpiner.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import alpiner.app.AlpinerApp
import alpiner.app.R
import alpiner.app.distro.Alpine
import alpiner.app.distro.AlpineInstaller
import alpiner.app.prefs
import alpiner.app.service.TerminalService
import alpiner.app.session.sessionStore
import alpiner.app.util.Clipboard
import alpiner.app.util.StoragePermission
import alpiner.app.util.toast
import com.google.android.material.card.MaterialCardView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalActivity : AppCompatActivity() {

    private val installer get() = (application as AlpinerApp).alpineInstaller
    private val palette by lazy { Palette(this) }
    private val modifiers = ModifierState()

    private lateinit var terminalView: TerminalView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sessionListContainer: android.widget.LinearLayout
    private lateinit var extraKeysWrapper: android.widget.LinearLayout
    private lateinit var extraKeysPager: ViewPager2
    private lateinit var extraKeys: ExtraKeys

    private lateinit var installOverlay: View
    private lateinit var installTitle: TextView
    private lateinit var installProgress: android.widget.ProgressBar
    private lateinit var installStatus: TextView
    private lateinit var installRetry: Button

    private var exitingApp = false
    private var sawActiveSession = false
    private var lastImeVisible = false
    private var autohideKeys = false
    private lateinit var backend: TerminalBackend

    private val session: TerminalSession? get() = sessionStore.current

    companion object {
        /** Binder clips above this size are rejected with TransactionTooLargeException. */
        private const val CLIPBOARD_MAX_CHARS = 200_000

        /** Re-ask for All files access at most once a day. */
        private const val PERMISSION_ASK_THROTTLE_MS = 24L * 60 * 60 * 1000

        private const val MENU_COPY = 12
        private const val MENU_PASTE = 13
        private const val MENU_EXPORT = 14
        private const val MENU_SETTINGS = 15

        fun launchIntent(context: android.content.Context): Intent =
            Intent(context, TerminalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("Notifications will be suppressed; the terminal service will still run")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        bindViews()
        sessionStore.terminalView = terminalView

        backend = TerminalBackend(
            view = terminalView,
            modifiers = modifiers,
            onModifierConsumed = { extraKeys.consumeModifiers() },
            onEmulatorReady = { applyEmulatorColors() },
        )
        terminalView.setTerminalViewClient(backend)
        extraKeys = ExtraKeys(
            context = this,
            terminalView = terminalView,
            modifiers = modifiers,
            palette = palette,
            session = { session },
            onMenu = { toggleSessionsPanel() },
        )
        extraKeysPager.adapter = ExtraKeysPagerAdapter(
            onKeysPage = { row1, row2 -> extraKeys.attachRows(row1, row2) },
            onSendLine = { sendInputLine(it) },
        )

        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START)
        val closeDrawerOnBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { hideSessionsPanel() }
        }
        onBackPressedDispatcher.addCallback(this, closeDrawerOnBack)
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) { closeDrawerOnBack.isEnabled = true }
            override fun onDrawerClosed(drawerView: View) { closeDrawerOnBack.isEnabled = false }
        })

        registerForContextMenu(terminalView)
        setupImeVisibilityListener()
        findViewById<TextView>(R.id.new_session_button).setOnClickListener { requestNewSession() }
        installRetry.setOnClickListener { installer.install() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        observeSessions()
        observeInstallState()
        lifecycleScope.launch {
            // Exit from the notification means "quit Alpiner entirely": close
            // every activity in the task, not just this screen.
            sessionStore.exitSignal.collect {
                exitingApp = true
                finishAffinity()
            }
        }
    }

    private fun bindViews() {
        terminalView = findViewById(R.id.terminal_view)
        drawerLayout = findViewById(R.id.drawer_layout)
        sessionListContainer = findViewById(R.id.session_list_container)
        extraKeysWrapper = findViewById(R.id.extra_keys_wrapper)
        extraKeysPager = findViewById(R.id.extra_keys_pager)
        extraKeysPager.offscreenPageLimit = 1
        installOverlay = findViewById(R.id.install_overlay)
        installTitle = findViewById(R.id.install_title)
        installProgress = findViewById(R.id.install_progress)
        installStatus = findViewById(R.id.install_status)
        installRetry = findViewById(R.id.install_retry)
    }

    private fun observeSessions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionStore.state.collect { st ->
                    if (st.sessions.isEmpty()) {
                        // The list can only become empty after the store held
                        // sessions (the very first emission is empty too).
                        if (sawActiveSession && !exitingApp) finishAffinity()
                        updateDrawer()
                        return@collect
                    }
                    sawActiveSession = true
                    updateDrawer()
                    val current = st.current ?: return@collect
                    if (terminalView.mTermSession !== current) showSession(current)
                }
            }
        }
    }

    private fun observeInstallState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                installer.state.collect { state -> renderInstallState(state) }
            }
        }
    }

    /** Attaches [session] to the terminal view and requests a redraw. */
    private fun showSession(session: TerminalSession) {
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
    }

    private fun sendInputLine(text: String) {
        val s = session ?: return
        s.write(if (text.isEmpty()) "\r" else text + "\r")
    }

    private fun requestNewSession() {
        if (installer.isInstalled) {
            startTerminalService(TerminalService.ACTION_CREATE_SESSION)
        } else {
            installer.install()
        }
    }

    /** Session bootstrap from resume/install-completion; ignores a running install. */
    private fun ensureSession() {
        if (sessionStore.sessions.isNotEmpty()) return
        when {
            installer.isInstalled -> startTerminalService(TerminalService.ACTION_AUTO_CREATE_SESSION)
            installer.state.value !is AlpineInstaller.State.Running -> installer.install()
        }
    }

    private fun startTerminalService(action: String?) {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TerminalService::class.java).apply { this.action = action }
            )
        } catch (e: Exception) {
            // Android 12+ forbids starting a foreground service while the app
            // is backgrounded; onResume() retries once we are visible again.
            Log.e("TerminalActivity", "Foreground service start failed", e)
        }
    }

    // --- Sessions drawer ---

    private fun toggleSessionsPanel() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) hideSessionsPanel()
        else drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun hideSessionsPanel() {
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun switchToSession(index: Int) {
        if (index != sessionStore.currentIndex) sessionStore.switchTo(index)
    }

    private fun closeSession(index: Int) {
        // Closing the last session is allowed: the store becomes empty and
        // observeSessions() closes the app, exactly like typing `exit`.
        sessionStore.close(index)
    }

    private fun updateDrawer() {
        sessionListContainer.removeAllViews()
        val sessions = sessionStore.sessions
        if (sessions.isEmpty()) {
            sessionListContainer.addView(TextView(this).apply {
                text = "No sessions"
                setTextColor(palette.mutedText)
                textSize = 13f
                setPadding(16, 20, 16, 20)
            })
            return
        }
        sessions.indices.forEach { sessionListContainer.addView(buildSessionCard(it)) }
    }

    private fun buildSessionCard(i: Int): MaterialCardView {
        val isCurrent = i == sessionStore.currentIndex
        val card = layoutInflater.inflate(R.layout.item_session, sessionListContainer, false) as MaterialCardView
        card.setCardBackgroundColor(if (isCurrent) palette.extraKeysBg else 0)
        card.findViewById<TextView>(R.id.session_name).text = sessionStore.sessions[i].mSessionName
        card.findViewById<View>(R.id.session_dot).setBackgroundResource(
            if (isCurrent) R.drawable.session_dot_active else R.drawable.session_dot_inactive
        )
        card.setOnClickListener { switchToSession(i); hideSessionsPanel() }
        card.setOnLongClickListener { showRenameSessionDialog(i); true }
        card.findViewById<View>(R.id.session_close).setOnClickListener { closeSession(i) }
        return card
    }

    private fun showRenameSessionDialog(index: Int) {
        // Capture the session, not the index: the list may shrink (a session
        // exits) while the dialog is open.
        val session = sessionStore.sessions.getOrNull(index) ?: return
        val input = EditText(this).apply { setText(session.mSessionName) }
        AlertDialog.Builder(this)
            .setTitle("Rename session")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    session.mSessionName = newName
                    updateDrawer()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Install overlay ---

    private fun renderInstallState(state: AlpineInstaller.State) {
        when (state) {
            is AlpineInstaller.State.Idle -> setInstallOverlayVisible(false)
            is AlpineInstaller.State.Running -> {
                setInstallOverlayVisible(true)
                installTitle.text = "Installing ${Alpine.DISPLAY_NAME}..."
                installStatus.setTextColor(palette.terminalText)
                installRetry.visibility = View.GONE
                installProgress.visibility = View.VISIBLE
                val progress = state.progress
                if (progress.percent < 0) {
                    installProgress.isIndeterminate = true
                    installStatus.text = progress.detail
                } else {
                    installProgress.isIndeterminate = false
                    installProgress.progress = progress.percent
                    installStatus.text = "${progress.percent}% - ${progress.detail}"
                }
            }
            is AlpineInstaller.State.Failed -> {
                setInstallOverlayVisible(true)
                installTitle.text = "Installing ${Alpine.DISPLAY_NAME}..."
                installStatus.setTextColor(palette.errorText)
                installStatus.text = "Install failed:\n${state.message}"
                installProgress.visibility = View.GONE
                installRetry.visibility = View.VISIBLE
            }
            is AlpineInstaller.State.Done -> {
                setInstallOverlayVisible(false)
                installer.acknowledgeDone()
                toast("${Alpine.DISPLAY_NAME} installed", Toast.LENGTH_SHORT)
                ensureSession()
            }
        }
    }

    private fun setInstallOverlayVisible(visible: Boolean) {
        installOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        terminalView.visibility = if (visible) View.GONE else View.VISIBLE
        if (!visible) {
            installProgress.isIndeterminate = false
            installProgress.visibility = View.VISIBLE
            installStatus.text = "Preparing..."
        }
    }

    // --- Clipboard, copy/paste/export ---

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
            var text = it.emulator.getScreen().getTranscriptText()
            // A full 30k-row transcript exceeds the binder transaction limit
            // and crashes with TransactionTooLargeException; trim it and tell
            // the user where the complete output went instead.
            if (text.length > CLIPBOARD_MAX_CHARS) {
                text = text.take(CLIPBOARD_MAX_CHARS)
                toast("Output truncated to ${CLIPBOARD_MAX_CHARS / 1000}k chars for the clipboard", Toast.LENGTH_SHORT)
            }
            Clipboard.copy(this, text)
            toast("Copied entire output (${text.length} chars)", Toast.LENGTH_SHORT)
        }
    }

    private fun pasteClipboard() {
        val text = Clipboard.primaryText(this) ?: return
        terminalView.mEmulator?.paste(text)
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
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                var dir = File(Environment.getExternalStorageDirectory(), "Alpiner/exports")
                dir.mkdirs()
                if (!dir.exists()) dir = File(filesDir, "exports").apply { mkdirs() }
                val f = File(dir, "alpine-${System.currentTimeMillis()}.txt")
                f.writeText(snapshot)
                f.absolutePath
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { toast("Exported: $it", Toast.LENGTH_SHORT) },
                    onFailure = { toast("Export failed: ${it.message}") },
                )
            }
        }
    }

    // --- Window and input plumbing ---

    private fun setupImeVisibilityListener() {
        val rootView = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (visible != lastImeVisible) {
                lastImeVisible = visible
                extraKeys.updateModifierButtons()
                updateExtraKeysVisibility()
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun updateExtraKeysVisibility() {
        extraKeysWrapper.visibility =
            if (autohideKeys && !lastImeVisible) View.GONE else View.VISIBLE
    }

    private fun applyEmulatorColors() {
        val emulator = terminalView.mEmulator ?: return
        val colors = emulator.mColors.mCurrentColors
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = palette.terminalText
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = palette.terminalBg
        colors[TextStyle.COLOR_INDEX_CURSOR] = palette.terminalText
        terminalView.invalidate()
    }

    private fun syncKeepScreenOn() {
        val keepScreenOn = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (prefs.keepScreenOn) window.addFlags(keepScreenOn)
        else window.clearFlags(keepScreenOn)
    }

    override fun onResume() {
        super.onResume()
        autohideKeys = prefs.autohideKeys
        extraKeys.sync()
        if (!StoragePermission.isAccessible()) {
            // All-files access is re-requested at most once a day so a denied
            // permission does not re-open Settings on every stop/start.
            val askTime = prefs.storageAskTime
            if (System.currentTimeMillis() - askTime > PERMISSION_ASK_THROTTLE_MS) {
                prefs.storageAskTime = System.currentTimeMillis()
                StoragePermission.requestAccess(this)
            }
        }
        terminalView.requestFocus()
        terminalView.onScreenUpdated()
        applyEmulatorColors()
        backend.applyFontSize()
        syncKeepScreenOn()
        extraKeys.updateModifierButtons()
        updateExtraKeysVisibility()
        // Covers an install that finished while the app was backgrounded:
        // the foreground-service start it attempted there may have been
        // rejected by the system.
        ensureSession()
    }

    override fun onDestroy() {
        // A config-change recreation must not kill an install in flight;
        // only a real exit (isFinishing) cancels it.
        if (isFinishing) installer.cancel()
        if (sessionStore.terminalView === terminalView) sessionStore.terminalView = null
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Heuristic top-edge band: taps this close to the window's top (and
        // the physical screen's) can follow a system-UI gesture that changed
        // IME state, so in auto-hide mode re-evaluate extra-keys visibility
        // before the tap lands.
        if (ev.action == MotionEvent.ACTION_DOWN && autohideKeys && ev.y < dp(40) && ev.rawY < dp(120)) {
            updateExtraKeysVisibility()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentFocus is EditText) return super.dispatchKeyEvent(event)
        if (session == null) return super.dispatchKeyEvent(event)
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
            event.keyCode !in TerminalModifier.ALL_KEY_CODES
        ) {
            extraKeys.consumeModifiers()
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

    override fun onContextItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_COPY -> { copySelectedText(); true }
            MENU_PASTE -> { pasteClipboard(); true }
            MENU_EXPORT -> { exportCurrentOutput(); true }
            MENU_SETTINGS -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            else -> super.onContextItemSelected(item)
        }
    }
}
