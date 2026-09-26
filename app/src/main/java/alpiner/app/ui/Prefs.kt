package alpiner.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.SeekBar
import kotlin.math.roundToInt

object Prefs {
    const val NAME = "settings"
    /** Terminal font size in dp (density-independent). */
    const val KEY_FONT_SIZE_DP = "font_size_dp"
    /** Legacy font size in raw pixels; migrated once to [KEY_FONT_SIZE_DP]. */
    private const val KEY_FONT_SIZE_PX_LEGACY = "font_size"
    const val KEY_SCROLLBACK = "scrollback"
    const val KEY_AUTOHIDE_KEYS = "autohide_keys"
    /** Holds a partial (CPU) wake lock while sessions run. */
    const val KEY_WAKELOCK = "wakelock"
    /** Keeps the display on while the terminal is visible. */
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    const val KEY_EXTRA_KEYS_ROW1 = "extra_keys_row1"
    const val KEY_EXTRA_KEYS_ROW2 = "extra_keys_row2"
    const val KEY_STORAGE_ASK_TIME = "storage_ask_time"

    const val SCROLLBACK_DEFAULT = 4
    const val FONT_SIZE_DEFAULT = 10
    const val FONT_SIZE_MIN = 6
    const val FONT_SIZE_MAX = 24
    /** Re-ask for All files access at most once a day. */
    const val PERMISSION_ASK_THROTTLE_MS = 24L * 60 * 60 * 1000
    const val KEY_REPEAT_INITIAL_DELAY = 400L
    const val KEY_REPEAT_DELAY = 80L
    const val UV_THREADPOOL_SIZE = 16
    const val ULIMIT_NOFILE = 65536
    const val ULIMIT_NPROC = 65536
    const val WAKELOCK_DEFAULT = true
    const val KEEP_SCREEN_ON_DEFAULT = false
    // The em-dash key writes a literal "-" to the terminal; intentional.
    const val EXTRA_KEYS_ROW1_DEFAULT = "\u2630 ALT ESC \u25B2 \u2014 /"
    const val EXTRA_KEYS_ROW2_DEFAULT = "TAB SHIFT \u25C0 \u25BC \u25B6 CTRL"

    val SCROLLBACK_ROWS = intArrayOf(500, 1000, 2000, 3000, 5000, 7500, 10000, 15000, 20000, 30000)

    const val CONNECT_TIMEOUT_MS = 30000
    const val READ_TIMEOUT_MS = 120000

    /**
     * Font size in dp. Older builds stored raw pixels under a different key;
     * that value is converted with the current density on first read.
     */
    fun fontSizeDp(context: Context): Int {
        val prefs = context.prefs()
        if (!prefs.contains(KEY_FONT_SIZE_DP) && prefs.contains(KEY_FONT_SIZE_PX_LEGACY)) {
            val density = context.resources.displayMetrics.density
            val migrated = (prefs.getInt(KEY_FONT_SIZE_PX_LEGACY, 0) / density).roundToInt()
                .coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)
            prefs.edit()
                .putInt(KEY_FONT_SIZE_DP, migrated)
                .remove(KEY_FONT_SIZE_PX_LEGACY)
                .apply()
            return migrated
        }
        return prefs.getInt(KEY_FONT_SIZE_DP, FONT_SIZE_DEFAULT).coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)
    }
}

fun Context.prefs(): SharedPreferences = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

fun simpleSeekBarListener(onProgress: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
    object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgress(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
