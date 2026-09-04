package alpiner.app.util

import android.app.Activity
import android.content.Intent

object IntentStarter {

    /**
     * Starts the first intent [activity] can launch, trying each in order
     * (specific settings screen first, generic fallback after). Returns
     * false when every attempt failed (no handler, SecurityException, ...).
     */
    fun startFirstAvailable(activity: Activity, vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }
        return false
    }
}
