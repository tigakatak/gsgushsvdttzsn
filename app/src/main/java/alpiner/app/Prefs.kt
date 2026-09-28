package alpiner.app

import android.content.Context
import androidx.core.content.edit

/** Typed access to the user settings; every key and default lives here. */
class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Terminal font size in dp (density-independent). */
    var fontSizeDp: Int
        get() = prefs.getInt(KEY_FONT_SIZE_DP, FONT_SIZE_DEFAULT).coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)
        set(value) = prefs.edit { putInt(KEY_FONT_SIZE_DP, value.coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)) }

    /** Index into [SCROLLBACK_ROWS]. */
    var scrollbackIndex: Int
        get() = prefs.getInt(KEY_SCROLLBACK, SCROLLBACK_DEFAULT).coerceIn(SCROLLBACK_ROWS.indices)
        set(value) = prefs.edit { putInt(KEY_SCROLLBACK, value) }

    val scrollbackRows: Int get() = SCROLLBACK_ROWS[scrollbackIndex]

    var autohideKeys: Boolean
        get() = prefs.getBoolean(KEY_AUTOHIDE_KEYS, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTOHIDE_KEYS, value) }

    /** Holds a partial (CPU) wake lock while sessions run. */
    var wakelock: Boolean
        get() = prefs.getBoolean(KEY_WAKELOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_WAKELOCK, value) }

    /** Keeps the display on while the terminal is visible. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    var extraKeysRow1: String
        get() = prefs.getString(KEY_EXTRA_KEYS_ROW1, null) ?: EXTRA_KEYS_ROW1_DEFAULT
        set(value) = prefs.edit { putString(KEY_EXTRA_KEYS_ROW1, value.trim()) }

    var extraKeysRow2: String
        get() = prefs.getString(KEY_EXTRA_KEYS_ROW2, null) ?: EXTRA_KEYS_ROW2_DEFAULT
        set(value) = prefs.edit { putString(KEY_EXTRA_KEYS_ROW2, value.trim()) }

    /** Both extra-keys rows split into their button labels. */
    val extraKeyLabels: List<List<String>>
        get() = listOf(extraKeysRow1, extraKeysRow2).map { row ->
            row.split(WHITESPACE).filter { it.isNotEmpty() }
        }

    /** When All files access was last requested (throttles the Settings prompt). */
    var storageAskTime: Long
        get() = prefs.getLong(KEY_STORAGE_ASK_TIME, 0L)
        set(value) = prefs.edit { putLong(KEY_STORAGE_ASK_TIME, value) }

    companion object {
        private const val NAME = "settings"
        private const val KEY_FONT_SIZE_DP = "font_size_dp"
        private const val KEY_SCROLLBACK = "scrollback"
        private const val KEY_AUTOHIDE_KEYS = "autohide_keys"
        private const val KEY_WAKELOCK = "wakelock"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_EXTRA_KEYS_ROW1 = "extra_keys_row1"
        private const val KEY_EXTRA_KEYS_ROW2 = "extra_keys_row2"
        private const val KEY_STORAGE_ASK_TIME = "storage_ask_time"

        private val WHITESPACE = Regex("\\s+")

        const val FONT_SIZE_DEFAULT = 10
        const val FONT_SIZE_MIN = 6
        const val FONT_SIZE_MAX = 24

        const val SCROLLBACK_DEFAULT = 4
        val SCROLLBACK_ROWS = intArrayOf(500, 1000, 2000, 3000, 5000, 7500, 10000, 15000, 20000, 30000)

        // The em-dash key writes a literal "-" to the terminal; intentional.
        const val EXTRA_KEYS_ROW1_DEFAULT = "\u2630 ALT ESC \u25B2 \u2014 /"
        const val EXTRA_KEYS_ROW2_DEFAULT = "TAB SHIFT \u25C0 \u25BC \u25B6 CTRL"
    }
}

val Context.prefs: Prefs get() = Prefs(this)
