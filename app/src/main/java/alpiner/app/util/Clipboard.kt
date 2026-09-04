package alpiner.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Single clipboard access point for both the view client and the context menu. */
object Clipboard {
    private const val LABEL = "terminal"

    fun copy(context: Context, text: String) {
        manager(context)?.setPrimaryClip(ClipData.newPlainText(LABEL, text))
    }

    fun primaryText(context: Context): String? =
        manager(context)?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()

    private fun manager(context: Context): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
}
