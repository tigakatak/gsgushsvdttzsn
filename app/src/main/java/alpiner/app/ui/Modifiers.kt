package alpiner.app.ui

import android.view.KeyEvent
import com.termux.terminal.KeyHandler

/**
 * Sticky terminal modifier keys. Each constant carries its KeyHandler bit
 * and the hardware keycodes that map to it, so every consumer (extra-keys
 * row, hardware-key dispatch, the terminal backend) derives from this one
 * enum instead of keeping parallel label lists.
 */
enum class TerminalModifier(val keyMod: Int, val keyCodes: Set<Int>) {
    CTRL(
        KeyHandler.KEYMOD_CTRL,
        setOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT),
    ),
    ALT(
        KeyHandler.KEYMOD_ALT,
        setOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT),
    ),
    SHIFT(
        KeyHandler.KEYMOD_SHIFT,
        setOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT),
    );

    companion object {
        /** Hardware keycodes of every modifier key. */
        val ALL_KEY_CODES: Set<Int> = entries.flatMapTo(mutableSetOf()) { it.keyCodes }

        /** The modifier shown by an extra-keys button [label], if any. */
        fun forLabel(label: String): TerminalModifier? =
            entries.firstOrNull { it.name == label }
    }
}

/**
 * Single source of truth for active sticky modifiers, shared by the
 * extra-keys row (writer) and [TerminalBackend] (reader, via TerminalView's
 * readControlKey/readAltKey/readShiftKey callbacks). Main thread only.
 */
class ModifierState {
    private val active = mutableSetOf<TerminalModifier>()

    fun isActive(modifier: TerminalModifier): Boolean = modifier in active

    fun any(): Boolean = active.isNotEmpty()

    fun toggle(modifier: TerminalModifier) {
        if (!active.add(modifier)) active.remove(modifier)
    }

    fun clear() = active.clear()

    /** OR-ed [TerminalModifier.keyMod] bits of every active modifier. */
    fun keyMod(): Int = active.fold(0) { acc, modifier -> acc or modifier.keyMod }
}
