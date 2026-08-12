package com.redtermapp.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.SeekBar

object Prefs {
    const val NAME = "settings"
    const val KEY_FONT = "font"
    const val KEY_THEME = "theme"
    const val KEY_FONT_SIZE = "font_size"
    const val KEY_SCROLLBACK = "scrollback"
    const val KEY_TERMINAL_OPACITY = "terminal_opacity"
    const val KEY_AUTOHIDE_KEYS = "autohide_keys"
    const val KEY_WAKELOCK = "wakelock"
    const val KEY_LOCK_ENABLED = "lock_enabled"
    const val KEY_LOCK_PIN = "lock_pin"
    const val KEY_LAST_DISTRO = "last_distro"
    const val KEY_SNIPPETS = "snippets"

    const val SCROLLBACK_DEFAULT = 4
    const val FONT_SIZE_DEFAULT = 20
    const val OPACITY_DEFAULT = 10
    const val UNLOCK_WINDOW_MS = 120_000L
    const val PERMISSION_ASK_THROTTLE_MS = 8000L
    const val KEY_REPEAT_INITIAL_DELAY = 400L
    const val KEY_REPEAT_DELAY = 80L
    const val SESSION_ID_LENGTH = 16
    const val UV_THREADPOOL_SIZE = 16
    const val ULIMIT_NOFILE = 65536
    const val ULIMIT_NPROC = 65536
    const val WAKELOCK_DEFAULT = true

    val SCROLLBACK_ROWS = intArrayOf(500, 1000, 2000, 3000, 5000, 7500, 10000, 15000, 20000, 30000)

    val BUILT_IN_FONTS = listOf(
        "JetBrains Mono", "Fira Code", "Source Code Pro", "Ubuntu Mono",
        "monospace", "Droid Sans Mono", "Noto Sans Mono", "Cascadia Code"
    )

    val THEME_NAMES = listOf(
        "Catppuccin Dark", "AMOLED Black", "Green Terminal", "Red Terminal",
        "Light", "Dracula", "Nord", "Tokyo Night", "Gruvbox Dark", "Custom", "Dynamic"
    )
    val THEME_VALUES = listOf(
        "default", "amoled", "green", "red",
        "light", "dracula", "nord", "tokyo", "gruvbox", "custom", "dynamic"
    )

    val FONT_ASSET_MAP = mapOf(
        "JetBrains Mono" to "fonts/JetBrainsMono.ttf",
        "Fira Code" to "fonts/FiraCode.ttf",
        "Source Code Pro" to "fonts/SourceCodePro.ttf",
        "Ubuntu Mono" to "fonts/UbuntuMono.ttf",
        "Droid Sans Mono" to "fonts/DroidSansMono.ttf",
        "Noto Sans Mono" to "fonts/NotoSansMono.ttf",
        "Cascadia Code" to "fonts/CascadiaCode.ttf"
    )

    val FONT_MENU_IDS = listOf(
        71 to "JetBrains Mono", 72 to "Fira Code", 73 to "Source Code Pro",
        74 to "Ubuntu Mono", 75 to "monospace", 76 to "Droid Sans Mono",
        77 to "Noto Sans Mono", 78 to "Cascadia Code"
    )

    const val CUSTOM_FONT_MENU_BASE = 100

    const val CONNECT_TIMEOUT_MS = 30000
    const val READ_TIMEOUT_MS = 120000
}

fun Context.prefs(): SharedPreferences = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

fun fontDisplayName(fileName: String): String =
    fileName.removeSuffix(".ttf").removeSuffix(".TTF").removeSuffix(".otf").removeSuffix(".OTF")

fun String.capitalized(): String = replaceFirstChar { it.uppercase() }

fun simpleSeekBarListener(onProgress: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
    object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            onProgress(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
