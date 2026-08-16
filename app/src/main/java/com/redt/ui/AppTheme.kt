package com.redt.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.google.android.material.card.MaterialCardView
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

    /**
     * Static values that Theme_RedTApp_Custom resolves the theme attributes
     * to (Catppuccin defaults). Views inflated from XML in custom mode carry
     * these; remap them to the user's custom colors.
     */
    private val STATIC_BG = 0xFF1E1E2E.toInt()
    private val STATIC_TEXT = 0xFFCDD6F4.toInt()
    private val STATIC_EXTRA_BG = 0xFF181825.toInt()
    private val STATIC_ACCENT = 0xFF89B4FA.toInt()

    /**
     * Recolors the already-inflated view tree of [activity] when the custom
     * theme is active. XML attributes (terminalBg/terminalText/themeAccent/
     * extraKeysBg) resolve to the static Catppuccin values in custom mode;
     * this maps those exact values to the user's picked colors. Views created
     * programmatically already go through the custom-aware themeColor and
     * carry the custom values directly, so they are left untouched.
     */
    fun recolorCustomChrome(activity: Activity) {
        val prefs = activity.prefs()
        val name = prefs.getString(Prefs.KEY_THEME, "amoled") ?: "amoled"
        if (name != "custom") return
        val bg = prefs.getInt("custom_bg", 0xFF1E1E2E.toInt())
        val text = prefs.getInt("custom_text", 0xFFCDD6F4.toInt())
        val accent = prefs.getInt("custom_primary", 0xFF89B4FA.toInt())
        val extraBg = prefs.getInt("custom_extra_bg", 0xFF181825.toInt())
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        recolor(content, bg, text, accent, extraBg)
    }

    private fun recolor(view: View, bg: Int, text: Int, accent: Int, extraBg: Int) {
        if (view is TextView) {
            when (view.currentTextColor) {
                STATIC_TEXT -> view.setTextColor(text)
                STATIC_ACCENT -> view.setTextColor(accent)
            }
        }
        if (view is MaterialCardView) {
            when (view.cardBackgroundColor.defaultColor) {
                STATIC_BG -> view.setCardBackgroundColor(bg)
                STATIC_EXTRA_BG -> view.setCardBackgroundColor(extraBg)
            }
        } else {
            (view.background as? ColorDrawable)?.let { drawable ->
                when (drawable.color) {
                    STATIC_BG -> view.setBackgroundColor(bg)
                    STATIC_EXTRA_BG -> view.setBackgroundColor(extraBg)
                }
            }
        }
        if (view is Spinner) {
            (view.popupBackground as? ColorDrawable)?.let { drawable ->
                if (drawable.color == STATIC_BG) {
                    view.setPopupBackgroundDrawable(ColorDrawable(bg))
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                recolor(view.getChildAt(i), bg, text, accent, extraBg)
            }
        }
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
    customThemeColor(attr)?.let { return it }
    val ta = theme.obtainStyledAttributes(intArrayOf(attr))
    val c = ta.getColor(0, default)
    ta.recycle()
    return c
}

fun Context.mutedTextColor(): Int = themeColor(R.attr.mutedText, 0xFF6C7086.toInt())
fun Context.hintColor(): Int = themeColor(R.attr.hintText, 0xFFCDD6F4.toInt())
fun Context.modifierHighlightColor(): Int = themeColor(R.attr.modifierHighlight, 0xFF45475A.toInt())

/**
 * Custom-theme mapping for attributes the user can configure. Returns null
 * for every other theme/attribute so the static style resolution stays the
 * single source of truth for built-in themes.
 */
private fun Context.customThemeColor(attr: Int): Int? {
    val name = prefs().getString(Prefs.KEY_THEME, "amoled") ?: "amoled"
    if (name != "custom") return null
    return when (attr) {
        R.attr.themeAccent -> prefs().getInt("custom_primary", 0xFF89B4FA.toInt())
        R.attr.terminalBg -> prefs().getInt("custom_bg", 0xFF1E1E2E.toInt())
        R.attr.terminalText -> prefs().getInt("custom_text", 0xFFCDD6F4.toInt())
        R.attr.extraKeysBg -> prefs().getInt("custom_extra_bg", 0xFF181825.toInt())
        R.attr.hintText -> prefs().getInt("custom_text", 0xFFCDD6F4.toInt())
            .let { (it and 0x00FFFFFF) or 0x66000000 }
        else -> null
    }
}

fun Context.customFontFiles(): List<File> =
    File(filesDir, "fonts").listFiles { f ->
        f.isFile && (f.extension.equals("ttf", true) || f.extension.equals("otf", true))
    }?.sortedBy { it.name.lowercase() } ?: emptyList()
