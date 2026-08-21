package com.redt.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.redt.BuildConfig
import com.redt.R
import com.redt.distro.DistroInstaller
import com.redt.service.TerminalService
import com.redt.session.terminalSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private val installer by lazy { DistroInstaller(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Exit from the notification quits the app entirely; close Settings
        // as well, even if it is the only activity alive in the task.
        lifecycleScope.launch {
            terminalSessionStore.exitSignal.collect { finishAffinity() }
        }

        val prefs = prefs()

        val fontSlider = findViewById<SeekBar>(R.id.font_size_slider)
        val wakelockSwitch = findViewById<Switch>(R.id.wakelock_switch)
        val versionInfo = findViewById<TextView>(R.id.version_info)

        fontSlider.progress = prefs.getInt(Prefs.KEY_FONT_SIZE, Prefs.FONT_SIZE_DEFAULT)
        wakelockSwitch.isChecked = prefs.getBoolean(Prefs.KEY_WAKELOCK, Prefs.WAKELOCK_DEFAULT)

        fontSlider.setOnSeekBarChangeListener(simpleSeekBarListener { progress ->
            prefs.edit().putInt(Prefs.KEY_FONT_SIZE, progress.coerceIn(Prefs.FONT_SIZE_MIN, Prefs.FONT_SIZE_MAX)).apply()
        })

        wakelockSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Prefs.KEY_WAKELOCK, isChecked).apply()
            val svc = Intent(this, TerminalService::class.java)
            if (isChecked) {
                svc.action = TerminalService.ACTION_ACQUIRE
                ContextCompat.startForegroundService(this, svc)
            } else {
                svc.action = TerminalService.ACTION_RELEASE
                startService(svc)
            }
        }

        val batteryBtn = findViewById<TextView>(R.id.battery_opt_btn)
        batteryBtn.setOnClickListener { requestBatteryOptimizationExemption() }
        updateBatteryOptimizationLabel(batteryBtn)

        val scrollbackSlider = findViewById<SeekBar>(R.id.scrollback_slider)
        val autohideSwitch = findViewById<Switch>(R.id.autohide_keys_switch)
        val opacitySlider = findViewById<SeekBar>(R.id.opacity_slider)

        scrollbackSlider.progress = prefs.getInt(Prefs.KEY_SCROLLBACK, Prefs.SCROLLBACK_DEFAULT)
        autohideSwitch.isChecked = prefs.getBoolean(Prefs.KEY_AUTOHIDE_KEYS, false)
        opacitySlider.progress = prefs.getInt(Prefs.KEY_TERMINAL_OPACITY, Prefs.OPACITY_DEFAULT)

        scrollbackSlider.setOnSeekBarChangeListener(simpleSeekBarListener { progress ->
            prefs.edit().putInt(Prefs.KEY_SCROLLBACK, progress).apply()
        })

        autohideSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Prefs.KEY_AUTOHIDE_KEYS, isChecked).apply()
        }

        val defaultRow1 = "\u2630 ESC \u25B2 \u2014 /"
        val defaultRow2 = "TAB \u25C0 \u25BC \u25B6 CTRL"
        val row1Input = findViewById<EditText>(R.id.extra_keys_row1_input)
        val row2Input = findViewById<EditText>(R.id.extra_keys_row2_input)
        row1Input.setText(prefs.getString("extra_keys_row1", defaultRow1))
        row2Input.setText(prefs.getString("extra_keys_row2", defaultRow2))
        findViewById<TextView>(R.id.save_extra_keys_btn).setOnClickListener {
            prefs.edit()
                .putString("extra_keys_row1", row1Input.text.toString().trim())
                .putString("extra_keys_row2", row2Input.text.toString().trim())
                .apply()
            Toast.makeText(this, "Extra keys saved", Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.reset_extra_keys_btn).setOnClickListener {
            row1Input.setText(defaultRow1)
            row2Input.setText(defaultRow2)
            prefs.edit()
                .putString("extra_keys_row1", row1Input.text.toString().trim())
                .putString("extra_keys_row2", row2Input.text.toString().trim())
                .apply()
            Toast.makeText(this, "Extra keys reset", Toast.LENGTH_SHORT).show()
        }

        opacitySlider.setOnSeekBarChangeListener(simpleSeekBarListener { progress ->
            prefs.edit().putInt(Prefs.KEY_TERMINAL_OPACITY, progress).apply()
        })

        findViewById<TextView>(R.id.export_config_btn).setOnClickListener {
            try {
                val json = org.json.JSONObject().apply {
                    put("font_size", prefs.getInt("font_size", 20))
                    put("scrollback", prefs.getInt("scrollback", 4))
                    put("terminal_opacity", prefs.getInt("terminal_opacity", 10))
                    put("autohide_keys", prefs.getBoolean("autohide_keys", false))
                    put("wakelock", prefs.getBoolean(Prefs.KEY_WAKELOCK, Prefs.WAKELOCK_DEFAULT))
                }
                val fileName = "RedT_config.json"
                val file = java.io.File(getExternalFilesDir(null), fileName)
                file.writeText(json.toString(2))
                Toast.makeText(this, "Config exported to $fileName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<TextView>(R.id.import_config_btn).setOnClickListener {
            try {
                val candidates = listOf(
                    java.io.File(android.os.Environment.getExternalStorageDirectory(), "RedT/RedT_config.json"),
                    java.io.File(getExternalFilesDir(null), "RedT_config.json")
                )
                val file = candidates.firstOrNull { it.exists() }
                if (file == null) {
                    Toast.makeText(this, "No RedT_config.json found", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val json = org.json.JSONObject(file.readText())
                val edit = prefs.edit()
                edit.putInt("font_size", json.optInt("font_size", 20))
                edit.putInt("scrollback", json.optInt("scrollback", 4))
                edit.putInt("terminal_opacity", json.optInt("terminal_opacity", 10))
                edit.putBoolean("autohide_keys", json.optBoolean("autohide_keys", false))
                edit.putBoolean("wakelock", json.optBoolean("wakelock", Prefs.WAKELOCK_DEFAULT))
                edit.apply()
                Toast.makeText(this, "Config imported from ${file.name}", Toast.LENGTH_LONG).show()
                recreate()
            } catch (e: Exception) {
                Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<TextView>(R.id.backup_btn).setOnClickListener {
            if (!com.redt.util.StoragePermission.isAccessible()) {
                Toast.makeText(this, "Grant All files access to use /sdcard/RedT", Toast.LENGTH_LONG).show()
                com.redt.util.StoragePermission.requestAccess(this)
                return@setOnClickListener
            }
            val installed = installer.getInstalledDistros()
            if (installed.isEmpty()) {
                Toast.makeText(this, "No distros installed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
                    val names = installed.map { it.capitalized() }.toTypedArray()
            val selected = BooleanArray(installed.size)
            AlertDialog.Builder(this)
                .setTitle("Backup distros")
                .setMultiChoiceItems(names, selected) { _, which, isChecked -> selected[which] = isChecked }
                .setPositiveButton("Backup") { _, _ ->
                    val targets = installed.filterIndexed { i, _ -> selected[i] }
                    if (targets.isEmpty()) {
                        Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val outDir = DistroUi.backupDir(this)
                    val dialog = android.app.ProgressDialog(this).apply {
                        setTitle("Backing up")
                        setMessage("Creating backup archives...")
                        setIndeterminate(true)
                        setCancelable(false)
                    }
                    dialog.show()
                    lifecycleScope.launch(Dispatchers.IO) {
                        var done = 0
                        var failed = 0
                        for (distro in targets) {
                            if (installer.backup(distro, outDir)) done++ else failed++
                        }
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) dialog.dismiss()
                            val msg = if (failed == 0) {
                                "Backed up $done distro(s): $outDir"
                            } else {
                                "Backup: $done ok, $failed failed"
                            }
                            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<TextView>(R.id.restore_btn).setOnClickListener {
            if (!com.redt.util.StoragePermission.isAccessible()) {
                Toast.makeText(this, "Grant All files access to use /sdcard/RedT", Toast.LENGTH_LONG).show()
                com.redt.util.StoragePermission.requestAccess(this)
                return@setOnClickListener
            }
            val files = DistroUi.backupFiles(this)
            if (files.isEmpty()) {
                Toast.makeText(this, "No backups or tarballs found in /sdcard/RedT", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val names = files.map { it.name }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Restore Distro")
                .setItems(names) { _, which ->
                    val backupFile = files[which]
                    val distroName = backupFile.name
                        .removeSuffix("_backup.tar.gz")
                        .removeSuffix(".tar.xz")
                        .removeSuffix(".tar.gz")
                    val rootfsDir = try {
                        installer.getRootfsDir(distroName)
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(
                            this, "Invalid distro archive name: ${backupFile.name}", Toast.LENGTH_LONG
                        ).show()
                        return@setItems
                    }
                    // Prompt only for a distro that is actually installed:
                    // a leftover rootfs husk from a failed uninstall must not
                    // trigger a spurious overwrite confirmation.
                    if (installer.isInstalled(distroName)) {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setMessage("The existing rootfs will be deleted and replaced. Any running session for it will be closed.")
                            .setPositiveButton("Overwrite") { _, _ ->
                                restoreDistro(backupFile, distroName, rootfsDir)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        restoreDistro(backupFile, distroName, rootfsDir)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        versionInfo.text = "${getString(R.string.app_name)} v${BuildConfig.VERSION_NAME}"

        findViewById<TextView>(R.id.crash_logs_btn).setOnClickListener {
            val base = getExternalFilesDir(null) ?: filesDir
            val crashDir = File(base, "crash")
            val logs = crashDir.listFiles { f -> f.name.startsWith("crash_") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (logs.isEmpty()) {
                Toast.makeText(this, "No crash logs", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Crash logs")
                .setItems(logs.map { it.name }.toTypedArray()) { _, which ->
                    shareCrashLog(logs[which])
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

    }

    private fun shareCrashLog(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share crash log"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot share crash log: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreDistro(backupFile: java.io.File, distroName: String, rootfsDir: java.io.File) {
        val isBackup = backupFile.name.endsWith("_backup.tar.gz")
        val dialog = android.app.ProgressDialog(this).apply {
            setTitle(if (isBackup) "Restoring $distroName" else "Installing $distroName")
            setMessage("Extracting rootfs...")
            setIndeterminate(true)
            setCancelable(false)
        }
        dialog.show()

        lifecycleScope.launch {
            try {
                // A restore replaces the rootfs wholesale: kill any running
                // session for this distro first (same as deleteDistro).
                terminalSessionStore.removeSessionsForDistro(distroName)
                withContext(Dispatchers.IO) {
                    if (isBackup) {
                        installer.deleteRootfsSafe(rootfsDir)
                        rootfsDir.mkdirs()
                        // Extract with the bundled Java codecs instead of the
                        // device's tar: toybox tar fails on PAX/GNU long-name
                        // entries produced by the Java backup fallback and
                        // reports only "exit code 1".
                        installer.extractBackupArchive(backupFile, rootfsDir) { }
                        if (!File(rootfsDir, "etc/os-release").exists() &&
                            !File(rootfsDir, "bin/busybox").exists()) {
                            val subdirs = rootfsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
                            if (subdirs.size == 1) {
                                val nested = subdirs[0]
                                nested.listFiles()?.forEach { it.renameTo(File(rootfsDir, it.name)) }
                                nested.delete()
                            }
                        }
                        if (!File(rootfsDir, "etc/os-release").exists() &&
                            !File(rootfsDir, "bin/busybox").exists()
                        ) {
                            throw RuntimeException("Backup does not look like a RedT distro")
                        }
                        com.redt.util.Format.invalidate(rootfsDir)
                        installer.saveInstalled(distroName)
                        installer.setupRootfs(rootfsDir)
                    } else {
                        installer.installFromFile(backupFile, distroName) {}
                    }
                }
                if (!isFinishing && !isDestroyed) dialog.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    if (isBackup) "$distroName restored" else "$distroName installed",
                    Toast.LENGTH_LONG
                ).show()
                populateDistroList()
            } catch (e: Exception) {
                // A failed restore must not leave a half-extracted rootfs
                // behind: it would pile up and make the next attempt show a
                // spurious "Overwrite?" prompt for a distro that was never
                // actually installed.
                try {
                    installer.deleteRootfsSafe(rootfsDir)
                } catch (_: Exception) {}
                if (!isFinishing && !isDestroyed) dialog.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    if (isBackup) "Restore error: ${e.message}" else "Install error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun onAddDistroClick(v: View) {
        startActivity(Intent(this, WelcomeActivity::class.java).apply {
            putExtra(WelcomeActivity.EXTRA_SELECT_ONLY, true)
        })
    }

    private fun populateDistroList() {
        val container = findViewById<LinearLayout>(R.id.distro_list)
        container.removeAllViews()

        val installed = installer.getInstalledDistros()
        if (installed.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No distros installed yet"
                setTextColor(mutedTextColor())
                textSize = 14f
                setPadding(4, 8, 4, 8)
            })
            return
        }

        for (name in installed) {
            val rootfsDir = installer.getRootfsDir(name)
            val sizeLabel = DistroUi.buildDistroSizeLabel(this, rootfsDir, 11f).apply {
                setPadding(0, 2, 0, 0)
            }
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
                setCardBackgroundColor(themeColor(R.attr.terminalBg, 0xFF1E1E2E.toInt()))
                radius = 12f
                setOnClickListener {
                    TerminalActivity.launch(this@SettingsActivity, name)
                }
                setOnLongClickListener {
                    confirmDelete(name)
                    true
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16, 16, 16, 16)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        addView(TextView(context).apply {
                            text = name.capitalized()
                            setTextColor(themeColor(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                            textSize = 16f
                        })
                        addView(sizeLabel)
                    })
                    addView(TextView(context).apply {
                        text = "Launch \u203A"
                        setTextColor(0xFF89B4FA.toInt())
                        textSize = 16f
                    })
                })
            }
            container.addView(card)
        }
    }

    private fun confirmDelete(name: String) {
        DistroUi.confirmDelete(this, installer, name) { populateDistroList() }
    }

    override fun onResume() {
        super.onResume()
        populateDistroList()
        findViewById<TextView>(R.id.battery_opt_btn)?.let { updateBatteryOptimizationLabel(it) }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryOptimizationLabel(btn: TextView) {
        btn.text = if (isIgnoringBatteryOptimizations()) {
            "Battery optimization already disabled"
        } else {
            "Disable battery optimization"
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (isIgnoringBatteryOptimizations()) {
            Toast.makeText(this, "Already exempt from battery optimization", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open battery optimization settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

}
