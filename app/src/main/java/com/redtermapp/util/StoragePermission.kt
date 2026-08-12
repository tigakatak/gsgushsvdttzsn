package com.redtermapp.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

object StoragePermission {

    fun isAccessible(): Boolean = Environment.isExternalStorageManager()

    fun requestAccess(activity: Activity) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
            )
            return
        } catch (_: Exception) {}
        try {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (_: Exception) {}
    }
}
