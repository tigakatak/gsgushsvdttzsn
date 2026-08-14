package com.redt.ui

import android.content.Context
import android.os.Environment
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.redt.distro.DistroInstaller
import com.redt.session.terminalSessionStore
import com.redt.util.Format
import java.io.File

object DistroUi {

    suspend fun deleteDistro(activity: AppCompatActivity, installer: DistroInstaller, name: String) {
        activity.terminalSessionStore.removeSessionsForDistro(name)
        withContext(Dispatchers.IO) { installer.uninstall(name) }
        RedTWidgetProvider.updateAll(activity)
    }

    fun confirmDelete(
        activity: AppCompatActivity,
        installer: DistroInstaller,
        name: String,
        onDeleted: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle("Remove $name?")
            .setMessage("This will delete the rootfs, cached files and all data for $name, and kill any running session for it.")
            .setPositiveButton("Delete") { _, _ ->
                val dialog = android.app.ProgressDialog(activity).apply {
                    setTitle("Removing $name")
                    setMessage("Deleting rootfs...")
                    setIndeterminate(true)
                    setCancelable(false)
                }
                dialog.show()
                activity.lifecycleScope.launch {
                    try {
                        deleteDistro(activity, installer, name)
                        onDeleted()
                        android.widget.Toast.makeText(activity, "$name removed", android.widget.Toast.LENGTH_SHORT).show()
                    } finally {
                        if (!activity.isFinishing && !activity.isDestroyed) dialog.dismiss()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun buildDistroSizeLabel(context: Context, rootfsDir: File, textSize: Float): TextView {
        val cached = Format.cachedSize(rootfsDir)
        val label = TextView(context).apply {
            text = if (cached != null) Format.size(cached) else ""
            setTextColor(context.mutedTextColor())
            this.textSize = textSize
        }
        if (cached == null) {
            // dirSizeAsync posts its callback to the main thread unconditionally;
            // hold the label weakly so we don't retain (or mutate) it after the
            // activity is destroyed and the view is detached.
            val weakLabel = java.lang.ref.WeakReference(label)
            Format.dirSizeAsync(rootfsDir) { bytes ->
                val view = weakLabel.get() ?: return@dirSizeAsync
                if (view.isAttachedToWindow) {
                    view.text = Format.size(bytes)
                }
            }
        }
        return label
    }

    fun backupDir(context: Context): File {
        val shared = File(Environment.getExternalStorageDirectory(), "RedT")
        shared.mkdirs()
        return if (shared.exists()) {
            shared
        } else {
            File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
        }
    }

    fun backupFiles(context: Context): List<File> {
        val files = mutableListOf<File>()
        val dirs = listOf(
            File(Environment.getExternalStorageDirectory(), "RedT"),
            context.getExternalFilesDir(null)
        )
        for (dir in dirs) {
            dir?.listFiles { f ->
                f.name.endsWith(".tar.gz") || f.name.endsWith(".tar.xz")
            }?.let { files.addAll(it) }
        }
        return files.distinctBy { it.name }
    }
}
