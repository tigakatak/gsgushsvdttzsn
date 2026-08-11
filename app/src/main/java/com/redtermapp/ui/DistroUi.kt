package com.redtermapp.ui

import android.content.Context
import android.os.Environment
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.session.terminalSessionStore
import com.redtermapp.util.Format
import java.io.File

object DistroUi {

    fun deleteDistro(activity: AppCompatActivity, installer: DistroInstaller, name: String) {
        activity.terminalSessionStore.removeSessionsForDistro(name)
        installer.uninstall(name)
        RedTermWidgetProvider.updateAll(activity)
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
                deleteDistro(activity, installer, name)
                onDeleted()
                android.widget.Toast.makeText(activity, "$name removed", android.widget.Toast.LENGTH_SHORT).show()
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
            Format.dirSizeAsync(rootfsDir) { bytes -> label.text = Format.size(bytes) }
        }
        return label
    }

    fun backupDir(context: Context): File {
        val shared = File(Environment.getExternalStorageDirectory(), "RedTerm")
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
            File(Environment.getExternalStorageDirectory(), "RedTerm"),
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
