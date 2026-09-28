package alpiner.app.util

import android.content.Context
import android.widget.Toast

/** Shows a toast from any context (activity, service, application). */
fun Context.toast(message: String, duration: Int = Toast.LENGTH_LONG) {
    Toast.makeText(this, message, duration).show()
}
