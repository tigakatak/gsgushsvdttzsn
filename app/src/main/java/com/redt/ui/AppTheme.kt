package com.redt.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.redt.R

object AppTheme {
    fun apply(activity: ComponentActivity) {
        val bg = activity.themeColor(R.attr.terminalBg, 0xFF1E1E2E.toInt())
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(bg),
            navigationBarStyle = SystemBarStyle.dark(bg)
        )
    }
}

fun Context.themeColor(attr: Int, default: Int): Int {
    val ta = theme.obtainStyledAttributes(intArrayOf(attr))
    val c = ta.getColor(0, default)
    ta.recycle()
    return c
}

fun Context.mutedTextColor(): Int = themeColor(R.attr.mutedText, 0xFF6C7086.toInt())
fun Context.hintColor(): Int = themeColor(R.attr.hintText, 0xFFCDD6F4.toInt())
fun Context.modifierHighlightColor(): Int = themeColor(R.attr.modifierHighlight, 0xFF45475A.toInt())
