package alpiner.app.ui

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import alpiner.app.Prefs
import alpiner.app.prefs
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

/** TerminalView callbacks: pinch-zoom font size, tap-to-show-IME and sticky modifiers. */
class TerminalBackend(
    private val view: TerminalView,
    private val modifiers: ModifierState,
    private val onModifierConsumed: () -> Unit,
    private val onEmulatorReady: () -> Unit,
) : TerminalViewClient {

    private val context: Context = view.context.applicationContext

    /** Font size in dp (what the user configures); scaled by density for the renderer. */
    private var fontSizeDp = 0f

    init {
        applyFontSize()
    }

    /** Re-reads the font size preference (settings change, resume). */
    fun applyFontSize() {
        fontSizeDp = context.prefs.fontSizeDp.toFloat()
        applyTextSize()
    }

    private fun applyTextSize() {
        view.setTextSize((fontSizeDp * context.resources.displayMetrics.density).roundToInt())
    }

    // Persist once, after the pinch gesture settles, not on every scale event.
    private val saveFontSize = Runnable { context.prefs.fontSizeDp = fontSizeDp.roundToInt() }

    override fun onScale(scale: Float): Float {
        fontSizeDp = (fontSizeDp * scale).coerceIn(
            Prefs.FONT_SIZE_MIN.toFloat(),
            Prefs.FONT_SIZE_MAX.toFloat(),
        )
        applyTextSize()
        view.removeCallbacks(saveFontSize)
        view.postDelayed(saveFontSize, 300L)
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        view.requestFocus()
        view.post {
            val imeVisible = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (!imeVisible) {
                context.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    override fun readControlKey(): Boolean = modifiers.isActive(TerminalModifier.CTRL)
    override fun readAltKey(): Boolean = modifiers.isActive(TerminalModifier.ALT)
    override fun readShiftKey(): Boolean = modifiers.isActive(TerminalModifier.SHIFT)
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (modifiers.any()) view.post { onModifierConsumed() }
        return false
    }

    override fun onEmulatorSet() = onEmulatorReady()

    // F1-F12 (with modifiers) are already mapped by KeyHandler via
    // handleKeyCode(); claiming keys here would drop Shift/Ctrl+F-keys.
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
}
