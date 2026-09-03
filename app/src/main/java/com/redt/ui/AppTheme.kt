package com.redt.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.redt.R

/**
 * Palette fallbacks. These mirror res/values/colors.xml; the theme normally
 * defines every attr, so they only surface in preview/lint contexts. Keep the
 * two lists in sync when changing colors.
 */
private const val DEFAULT_TERMINAL_BG = 0xFF1E1E2E.toInt()
private const val DEFAULT_TERMINAL_TEXT = 0xFFCDD6F4.toInt()
private const val DEFAULT_EXTRA_KEYS_BG = 0xFF181825.toInt()
private const val DEFAULT_THEME_ACCENT = 0xFF89B4FA.toInt()
private const val DEFAULT_MUTED_TEXT = 0xFF6C7086.toInt()
private const val DEFAULT_HINT_TEXT = 0x66CDD6F4.toInt()
private const val DEFAULT_MODIFIER_HIGHLIGHT = 0xFF45475A.toInt()

object AppTheme {
    fun apply(activity: ComponentActivity) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(activity.terminalBgColor()),
            navigationBarStyle = SystemBarStyle.dark(activity.terminalBgColor())
        )
    }
}

fun Context.themeColor(attr: Int, default: Int): Int {
    val ta = theme.obtainStyledAttributes(intArrayOf(attr))
    val c = ta.getColor(0, default)
    ta.recycle()
    return c
}

fun Context.terminalBgColor(): Int = themeColor(R.attr.terminalBg, DEFAULT_TERMINAL_BG)
fun Context.terminalTextColor(): Int = themeColor(R.attr.terminalText, DEFAULT_TERMINAL_TEXT)
fun Context.extraKeysBgColor(): Int = themeColor(R.attr.extraKeysBg, DEFAULT_EXTRA_KEYS_BG)
fun Context.themeAccentColor(): Int = themeColor(R.attr.themeAccent, DEFAULT_THEME_ACCENT)
fun Context.mutedTextColor(): Int = themeColor(R.attr.mutedText, DEFAULT_MUTED_TEXT)
fun Context.hintColor(): Int = themeColor(R.attr.hintText, DEFAULT_HINT_TEXT)
fun Context.modifierHighlightColor(): Int = themeColor(R.attr.modifierHighlight, DEFAULT_MODIFIER_HIGHLIGHT)
