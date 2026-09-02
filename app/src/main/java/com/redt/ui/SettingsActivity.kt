package com.redt.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.redt.BuildConfig
import com.redt.R
import com.redt.service.TerminalService
import com.redt.session.terminalSessionStore
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Exit from the notification quits the app entirely; close Settings
        // as well, even if it is the only activity alive in the task.
        lifecycleScope.launch {
            terminalSessionStore.exitSignal.collect { finishAffinity() }
        }
        findViewById<android.widget.ImageButton>(R.id.settings_back_btn).setOnClickListener { finish() }


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

        scrollbackSlider.progress = prefs.getInt(Prefs.KEY_SCROLLBACK, Prefs.SCROLLBACK_DEFAULT)
        autohideSwitch.isChecked = prefs.getBoolean(Prefs.KEY_AUTOHIDE_KEYS, false)

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

    override fun onResume() {
        super.onResume()
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
