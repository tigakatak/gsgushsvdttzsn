package alpiner.app.ui

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import alpiner.app.util.Clipboard
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

class TerminalBackend(
    val view: TerminalView,
    context: Context,
    private val modifiers: ModifierState
) : TerminalSessionClient, TerminalViewClient {

    private val context = context.applicationContext

    // Initialized once by the init block below via applyFontSize().
    private var fontSize = 0f

    init {
        applyFontSize()
    }
    var onSessionFinished: ((TerminalSession) -> Unit)? = null
    var onModifierConsumed: (() -> Unit)? = null
    var onEmulatorReady: (() -> Unit)? = null

    override fun onTextChanged(session: TerminalSession) {
        view.onScreenUpdated()
    }

    override fun onTitleChanged(session: TerminalSession) {}

    override fun onSessionFinished(session: TerminalSession) {
        view.post { onSessionFinished?.invoke(session) }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        Clipboard.copy(context, text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        Clipboard.primaryText(context)?.let { text -> view.mEmulator?.paste(text) }
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun onScale(scale: Float): Float {
        fontSize = (fontSize * scale).coerceIn(Prefs.FONT_SIZE_MIN.toFloat(), Prefs.FONT_SIZE_MAX.toFloat())
        val size = fontSize.roundToInt()
        view.setTextSize(size)
        // Persist once, after the pinch gesture settles, instead of writing
        // the preference on every scale event.
        view.removeCallbacks(saveFontSizeRunnable)
        view.postDelayed(saveFontSizeRunnable, 300L)
        return 1f
    }

    private val saveFontSizeRunnable = Runnable {
        context.prefs().edit().putInt(Prefs.KEY_FONT_SIZE, fontSize.roundToInt()).apply()
    }

    fun applyFontSize() {
        fontSize = context.prefs()
            .getInt(Prefs.KEY_FONT_SIZE, Prefs.FONT_SIZE_DEFAULT)
            .coerceIn(Prefs.FONT_SIZE_MIN, Prefs.FONT_SIZE_MAX).toFloat()
        view.setTextSize(fontSize.roundToInt())
    }

    override fun onSingleTapUp(e: MotionEvent) {
        view.requestFocus()
        view.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm == null) return@post
            val imeVisible = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (!imeVisible) {
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    /** Escape sequences for hardware F1-F12 keys. */
    private val fnKeySequences = mapOf(
        KeyEvent.KEYCODE_F1 to "\u001bOP",
        KeyEvent.KEYCODE_F2 to "\u001bOQ",
        KeyEvent.KEYCODE_F3 to "\u001bOR",
        KeyEvent.KEYCODE_F4 to "\u001bOS",
        KeyEvent.KEYCODE_F5 to "\u001b[15~",
        KeyEvent.KEYCODE_F6 to "\u001b[17~",
        KeyEvent.KEYCODE_F7 to "\u001b[18~",
        KeyEvent.KEYCODE_F8 to "\u001b[19~",
        KeyEvent.KEYCODE_F9 to "\u001b[20~",
        KeyEvent.KEYCODE_F10 to "\u001b[21~",
        KeyEvent.KEYCODE_F11 to "\u001b[23~",
        KeyEvent.KEYCODE_F12 to "\u001b[24~",
    )

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        val sequence = fnKeySequences[keyCode] ?: return false
        session.write(sequence)
        return true
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = modifiers.isActive(TerminalModifier.CTRL)
    override fun readAltKey(): Boolean = modifiers.isActive(TerminalModifier.ALT)
    override fun readShiftKey(): Boolean = modifiers.isActive(TerminalModifier.SHIFT)
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (modifiers.any()) {
            view.post { onModifierConsumed?.invoke() }
        }
        return false
    }

    override fun onEmulatorSet() {
        onEmulatorReady?.invoke()
    }

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e) }
}
