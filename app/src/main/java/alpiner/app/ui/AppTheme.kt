package alpiner.app.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import alpiner.app.R

/** The app's single, fixed color scheme (res/values/colors.xml), resolved once. */
class Palette(context: Context) {
    val terminalBg = ContextCompat.getColor(context, R.color.terminal_bg)
    val terminalText = ContextCompat.getColor(context, R.color.terminal_text)
    val extraKeysBg = ContextCompat.getColor(context, R.color.extra_keys_bg)
    val mutedText = ContextCompat.getColor(context, R.color.muted_text)
    val modifierHighlight = ContextCompat.getColor(context, R.color.modifier_highlight)
    val errorText = ContextCompat.getColor(context, R.color.error_text)
    val accent = ContextCompat.getColor(context, R.color.primary)
}

object AppTheme {
    /** Edge-to-edge with dark system bars in the terminal background color. */
    fun apply(activity: ComponentActivity) {
        val bg = ContextCompat.getColor(activity, R.color.terminal_bg)
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(bg),
            navigationBarStyle = SystemBarStyle.dark(bg),
        )
    }
}
