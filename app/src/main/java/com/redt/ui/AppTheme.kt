package com.redt.ui

import android.content.Context
import android.content.SharedPreferences
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.redt.R
import java.io.File

object AppTheme {
    fun themeRes(name: String): Int = when (name) {
        "red" -> R.style.Theme_RedTApp_Red
        "amoled" -> R.style.Theme_RedTApp_AMOLED
        "green" -> R.style.Theme_RedTApp_Green
        "light" -> R.style.Theme_RedTApp_Light
        "dracula" -> R.style.Theme_RedTApp_Dracula
        "nord" -> R.style.Theme_RedTApp_Nord
        "tokyo" -> R.style.Theme_RedTApp_Tokyo
        "gruvbox" -> R.style.Theme_RedTApp_Gruvbox
        "custom" -> R.style.Theme_RedTApp_Custom
        else -> R.style.Theme_RedTApp
    }

    fun resolveThemeColor(context: Context, prefs: SharedPreferences, attr: Int, default: Int): Int {
        val name = prefs.getString(Prefs.KEY_THEME, "amoled") ?: "amoled"
        val res = themeRes(name)
        return ContextThemeWrapper(context, res).themeColor(attr, default)
    }

    fun apply(activity: ComponentActivity) {
        val prefs = activity.prefs()
        val name = prefs.getString(Prefs.KEY_THEME, "amoled") ?: "amoled"
        val res = themeRes(name)
        activity.setTheme(res)
        val wrapped = ContextThemeWrapper(activity, res)
        val bg = if (name == "custom") {
            prefs.getInt("custom_bg", 0xFF1E1E2E.toInt())
        } else {
            wrapped.themeColor(R.attr.terminalBg, 0xFF1E1E2E.toInt())
        }
        val style = when {
            isLightColor(bg) -> SystemBarStyle.light(bg, bg)
            else -> SystemBarStyle.dark(bg)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun isLightColor(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b > 160
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

fun Context.customFontFiles(): List<File> =
    File(filesDir, "fonts").listFiles { f ->
        f.isFile && (f.extension.equals("ttf", true) || f.extension.equals("otf", true))
    }?.sortedBy { it.name.lowercase() } ?: emptyList()
