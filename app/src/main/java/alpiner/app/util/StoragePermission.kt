package alpiner.app.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

object StoragePermission {

    fun isAccessible(): Boolean = Environment.isExternalStorageManager()

    fun requestAccess(activity: Activity) {
        val scoped = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        val generic = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        IntentStarter.startFirstAvailable(activity, scoped, generic)
    }
}
