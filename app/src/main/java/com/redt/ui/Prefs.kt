package com.redt.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.SeekBar

object Prefs {
    const val NAME = "settings"
    const val KEY_FONT_SIZE = "font_size"
    const val KEY_SCROLLBACK = "scrollback"
    const val KEY_TERMINAL_OPACITY = "terminal_opacity"
    const val KEY_AUTOHIDE_KEYS = "autohide_keys"
    const val KEY_WAKELOCK = "wakelock"
    const val KEY_LAST_DISTRO = "last_distro"

    const val SCROLLBACK_DEFAULT = 4
    const val FONT_SIZE_DEFAULT = 20
    const val OPACITY_DEFAULT = 10
    const val PERMISSION_ASK_THROTTLE_MS = 8000L
    const val KEY_REPEAT_INITIAL_DELAY = 400L
    const val KEY_REPEAT_DELAY = 80L
    const val SESSION_ID_LENGTH = 16
    const val UV_THREADPOOL_SIZE = 16
    const val ULIMIT_NOFILE = 65536
    const val ULIMIT_NPROC = 65536
    const val WAKELOCK_DEFAULT = true

    val SCROLLBACK_ROWS = intArrayOf(500, 1000, 2000, 3000, 5000, 7500, 10000, 15000, 20000, 30000)

    const val CONNECT_TIMEOUT_MS = 30000
    const val READ_TIMEOUT_MS = 120000
}

fun Context.prefs(): SharedPreferences = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

fun String.capitalized(): String = replaceFirstChar { it.uppercase() }

fun simpleSeekBarListener(onProgress: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
    object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            onProgress(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
