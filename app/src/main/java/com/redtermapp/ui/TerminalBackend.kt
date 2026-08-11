package com.redtermapp.ui

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

class TerminalBackend(
    val view: TerminalView,
    context: Context
) : TerminalSessionClient, TerminalViewClient {

    private val context = context.applicationContext

    private var ctrlDown = false
    private var altDown = false
    private var fontSize = context.prefs().getInt(Prefs.KEY_FONT_SIZE, Prefs.FONT_SIZE_DEFAULT).toFloat()
    var onSessionFinished: ((TerminalSession) -> Unit)? = null
    var onLinkTap: ((String, Boolean) -> Unit)? = null
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
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (clip == null) return
        if (Build.VERSION.SDK_INT >= 33) {
            clip.setPrimaryClip(ClipData.newPlainText("terminal", text))
        } else {
            @Suppress("DEPRECATION") clip.setText(text)
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        val text = if (Build.VERSION.SDK_INT >= 33) {
            clip.primaryClip?.getItemAt(0)?.text
        } else {
            @Suppress("DEPRECATION") clip.text
        } ?: return
        view.mEmulator?.paste(text.toString())
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun onScale(scale: Float): Float {
        fontSize = (fontSize * scale).coerceIn(8f, 36f)
        val size = fontSize.roundToInt()
        view.setTextSize(size)
        context.prefs().edit().putInt(Prefs.KEY_FONT_SIZE, size).apply()
        return 1f
    }

    fun applyFontSize() {
        fontSize = context.prefs().getInt(Prefs.KEY_FONT_SIZE, Prefs.FONT_SIZE_DEFAULT).toFloat()
        view.setTextSize(fontSize.roundToInt())
    }

    fun setFontSize(size: Int) {
        val clamped = size.coerceIn(8, 36)
        fontSize = clamped.toFloat()
        context.prefs().edit().putInt(Prefs.KEY_FONT_SIZE, clamped).apply()
        view.setTextSize(clamped)
    }

    val currentFontSize: Int
        get() = fontSize.roundToInt()

    override fun onSingleTapUp(e: MotionEvent) {
        val session = view.mTermSession
        if (session != null && !view.isSelectingText) {
            val link = detectLinkAt(e)
            if (link != null) {
                onLinkTap?.invoke(link.first, link.second)
                return
            }
        }
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

    private val urlRegex = Regex("""https?://[^\s"'<>()\[\]{}]+|www\.[^\s"'<>()\[\]{}]+""")
    private val pathRegex = Regex("""(?:\.\.?/|~/|/)[^\s"'<>()\[\]{}]+""")

    private fun detectLinkAt(e: MotionEvent): Pair<String, Boolean>? {
        val emu = view.mEmulator ?: return null
        val colRow = try {
            view.getColumnAndRow(e, true)
        } catch (_: Exception) {
            return null
        } ?: return null
        val col = colRow[0]
        val row = colRow[1]
        val buffer = emu.getScreen()
        val line = try {
            buffer.getSelectedText(0, row, emu.mColumns, row)
        } catch (_: Exception) {
            return null
        }
        for (m in urlRegex.findAll(line)) {
            if (col in m.range) return m.value to false
        }
        for (m in pathRegex.findAll(line)) {
            if (col in m.range) {
                val raw = m.value.trimEnd(' ', ',', ';', ':', ')', '(', '"', '\'', ']', '}', '!', '?', '.')
                if (raw.length >= 2 && (raw.contains('/') || raw.startsWith("~/"))) return raw to true
            }
        }
        return null
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        val fKeySequence = when (keyCode) {
            KeyEvent.KEYCODE_F1 -> "\u001bOP"
            KeyEvent.KEYCODE_F2 -> "\u001bOQ"
            KeyEvent.KEYCODE_F3 -> "\u001bOR"
            KeyEvent.KEYCODE_F4 -> "\u001bOS"
            KeyEvent.KEYCODE_F5 -> "\u001b[15~"
            KeyEvent.KEYCODE_F6 -> "\u001b[17~"
            KeyEvent.KEYCODE_F7 -> "\u001b[18~"
            KeyEvent.KEYCODE_F8 -> "\u001b[19~"
            KeyEvent.KEYCODE_F9 -> "\u001b[20~"
            KeyEvent.KEYCODE_F10 -> "\u001b[21~"
            KeyEvent.KEYCODE_F11 -> "\u001b[23~"
            KeyEvent.KEYCODE_F12 -> "\u001b[24~"
            else -> null
        }
        if (fKeySequence != null) {
            session.write(fKeySequence)
            return true
        }
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = ctrlDown
    override fun readAltKey(): Boolean = altDown
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    fun setCtrl(v: Boolean) { ctrlDown = v }
    fun setAlt(v: Boolean) { altDown = v }

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (this.ctrlDown || this.altDown) {
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
