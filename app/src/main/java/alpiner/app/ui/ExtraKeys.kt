package alpiner.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import alpiner.app.prefs
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/**
 * The two configurable extra-keys rows: builds their buttons from the
 * settings, runs each key and highlights active sticky modifiers.
 */
internal class ExtraKeys(
    private val context: Context,
    private val terminalView: TerminalView,
    private val modifiers: ModifierState,
    private val palette: Palette,
    private val session: () -> TerminalSession?,
    private val onMenu: () -> Unit,
) {

    /**
     * One known key: the labels that select it (glyph or word), whether
     * holding it auto-repeats, whether it needs a live session, and what it
     * does. Unknown labels fall back to typing their text into the session.
     */
    private class Key(
        val labels: Set<String>,
        val repeatable: Boolean = false,
        val requiresSession: Boolean = true,
        val modifier: TerminalModifier? = null,
        val action: ExtraKeys.() -> Unit,
    )

    private val keysByLabel: Map<String, Key> = (
        listOf(
            Key(setOf("\u2630", "MENU"), requiresSession = false) { onMenu() },
            Key(setOf("ESC")) { session()?.writeCodePoint(false, 27) },
            Key(setOf("TAB")) { sendKey(KeyEvent.KEYCODE_TAB) },
            Key(setOf("\u25B2", "UP"), repeatable = true) { sendKey(KeyEvent.KEYCODE_DPAD_UP) },
            Key(setOf("\u25BC", "DOWN"), repeatable = true) { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) },
            Key(setOf("\u25C0", "LEFT"), repeatable = true) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) },
            Key(setOf("\u25B6", "RIGHT"), repeatable = true) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) },
            Key(setOf("HOME"), repeatable = true) { sendKey(KeyEvent.KEYCODE_MOVE_HOME) },
            Key(setOf("END"), repeatable = true) { sendKey(KeyEvent.KEYCODE_MOVE_END) },
            Key(setOf("INS"), repeatable = true) { sendKey(KeyEvent.KEYCODE_INSERT) },
            Key(setOf("DEL"), repeatable = true) { sendKey(KeyEvent.KEYCODE_FORWARD_DEL) },
            Key(setOf("\u232B", "BACKSPACE"), repeatable = true) { sendKey(KeyEvent.KEYCODE_DEL) },
            // The em-dash key intentionally writes a literal "-".
            Key(setOf("\u2014")) { session()?.write("-") },
        ) + TerminalModifier.entries.map { modifier ->
            Key(setOf(modifier.name), requiresSession = false, modifier = modifier) { toggleModifier(modifier) }
        }
    ).flatMap { key -> key.labels.map { it to key } }.toMap()

    private var rows: List<LinearLayout> = emptyList()
    private var appliedLabels: List<List<String>>? = null

    /** Shared by every repeatable button. */
    private val repeatHandler = Handler(Looper.getMainLooper())

    /** Mounts the rows once the pager has inflated the keys page. */
    fun attachRows(row1: LinearLayout, row2: LinearLayout) {
        rows = listOf(row1, row2)
        appliedLabels = null
        sync()
    }

    /** Rebuilds the rows when their configuration changed (e.g. edited in Settings). */
    fun sync() {
        if (rows.isEmpty()) return
        val labels = context.prefs.extraKeyLabels
        if (labels == appliedLabels) return
        appliedLabels = labels
        rows.zip(labels).forEach { (row, rowLabels) ->
            row.removeAllViews()
            rowLabels.forEach { row.addView(createButton(it)) }
        }
        updateModifierButtons()
    }

    /** Clears the sticky modifiers after a key used them. */
    fun consumeModifiers() {
        if (!modifiers.any()) return
        modifiers.clear()
        updateModifierButtons()
    }

    fun updateModifierButtons() {
        for (row in rows) {
            for (i in 0 until row.childCount) {
                val button = row.getChildAt(i) as? Button ?: continue
                val modifier = TerminalModifier.forLabel(button.text.toString()) ?: continue
                button.setBackgroundColor(if (modifiers.isActive(modifier)) palette.modifierHighlight else 0)
            }
        }
    }

    private fun toggleModifier(modifier: TerminalModifier) {
        modifiers.toggle(modifier)
        updateModifierButtons()
    }

    /** Sends [keyCode] with the currently active sticky modifiers. */
    private fun sendKey(keyCode: Int) {
        terminalView.handleKeyCode(keyCode, modifiers.keyMod())
    }

    private fun press(label: String) {
        val key = keysByLabel[label]
        if (key?.requiresSession != false && session() == null) return
        if (key != null) key.action(this) else session()?.write(label)
        if (key?.modifier == null) consumeModifiers()
    }

    private fun createButton(label: String): Button {
        val repeatable = keysByLabel[label]?.repeatable == true
        val isSymbol = label.length == 1 && !label[0].isLetterOrDigit()
        val repeatRunnable = object : Runnable {
            override fun run() {
                press(label)
                repeatHandler.postDelayed(this, KEY_REPEAT_DELAY_MS)
            }
        }
        return Button(context).apply {
            text = label
            setTextColor(palette.terminalText)
            textSize = if (isSymbol) 16f else 12f
            setBackgroundResource(0)
            isFocusable = false
            isFocusableInTouchMode = false
            setPadding(4, 4, 4, 4)
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                weight = 1f
                setMargins(2, 4, 2, 4)
                gravity = Gravity.CENTER
            }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        setBackgroundColor(palette.modifierHighlight)
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (repeatable) {
                            press(label)
                            repeatHandler.postDelayed(repeatRunnable, KEY_REPEAT_INITIAL_DELAY_MS)
                        }
                        repeatable
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        setBackgroundColor(0)
                        repeatHandler.removeCallbacks(repeatRunnable)
                        repeatable
                    }
                    else -> false
                }
            }
            setOnClickListener { if (!repeatable) press(label) }
        }
    }

    private companion object {
        const val KEY_REPEAT_INITIAL_DELAY_MS = 400L
        const val KEY_REPEAT_DELAY_MS = 80L
    }
}
