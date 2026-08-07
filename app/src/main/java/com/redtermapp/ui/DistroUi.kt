package com.redtermapp.ui

import android.app.Application
import android.content.Context
import android.os.Environment
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.util.Format
import java.io.File

object DistroUi {

    fun deleteDistro(activity: AppCompatActivity, installer: DistroInstaller, name: String) {
        TerminalViewModel.get(activity.application as Application).removeSessionsForDistro(name)
        installer.uninstall(name)
        RedTermWidgetProvider.updateAll(activity)
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
        File(Environment.getExternalStorageDirectory(), "RedTerm")
            .listFiles { f -> f.name.endsWith("_backup.tar.gz") }
            ?.let { files.addAll(it) }
        context.getExternalFilesDir(null)
            ?.listFiles { f -> f.name.endsWith("_backup.tar.gz") }
            ?.let { files.addAll(it) }
        return files.distinctBy { it.name }
    }
}
