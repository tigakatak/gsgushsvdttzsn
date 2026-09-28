package alpiner.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import alpiner.app.BuildConfig
import alpiner.app.Prefs
import alpiner.app.R
import alpiner.app.prefs
import alpiner.app.service.TerminalService
import alpiner.app.session.sessionStore
import alpiner.app.util.CrashHandler
import alpiner.app.util.IntentStarter
import alpiner.app.util.toast
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
            sessionStore.exitSignal.collect { finishAffinity() }
        }

        findViewById<ImageButton>(R.id.settings_back_btn).setOnClickListener { finish() }

        bindSlider(R.id.font_size_slider, prefs.fontSizeDp) { prefs.fontSizeDp = it }
        bindSlider(R.id.scrollback_slider, prefs.scrollbackIndex) { prefs.scrollbackIndex = it }
        bindSwitch(R.id.autohide_keys_switch, get = { prefs.autohideKeys }, set = { prefs.autohideKeys = it })
        bindSwitch(R.id.keep_screen_on_switch, get = { prefs.keepScreenOn }, set = { prefs.keepScreenOn = it })
        bindSwitch(
            R.id.wakelock_switch,
            get = { prefs.wakelock },
            set = { enabled ->
                prefs.wakelock = enabled
                // Without sessions the service is not running and will read
                // the pref when it starts; waking it here would only flash
                // the foreground notification.
                if (sessionStore.sessions.isNotEmpty()) {
                    ContextCompat.startForegroundService(
                        this, Intent(this, TerminalService::class.java)
                    )
                }
            },
        )

        findViewById<TextView>(R.id.battery_opt_btn).setOnClickListener { requestBatteryOptimizationExemption() }
        findViewById<TextView>(R.id.crash_logs_btn).setOnClickListener { showCrashLogs() }
        findViewById<TextView>(R.id.save_extra_keys_btn).setOnClickListener { saveExtraKeys() }
        findViewById<TextView>(R.id.reset_extra_keys_btn).setOnClickListener { resetExtraKeys() }

        findViewById<TextView>(R.id.version_info).text =
            "${getString(R.string.app_name)} v${BuildConfig.VERSION_NAME}"
    }

    private fun bindSlider(id: Int, initial: Int, set: (Int) -> Unit) {
        val slider = findViewById<SeekBar>(id)
        slider.progress = initial
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) set(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun bindSwitch(id: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val switch = findViewById<Switch>(id)
        switch.isChecked = get()
        switch.setOnCheckedChangeListener { _, isChecked -> set(isChecked) }
    }

    // --- Extra keys ---

    private fun saveExtraKeys() {
        prefs.extraKeysRow1 = findViewById<EditText>(R.id.extra_keys_row1_input).text.toString()
        prefs.extraKeysRow2 = findViewById<EditText>(R.id.extra_keys_row2_input).text.toString()
        toast("Extra keys saved", android.widget.Toast.LENGTH_SHORT)
    }

    private fun resetExtraKeys() {
        findViewById<EditText>(R.id.extra_keys_row1_input).setText(Prefs.EXTRA_KEYS_ROW1_DEFAULT)
        findViewById<EditText>(R.id.extra_keys_row2_input).setText(Prefs.EXTRA_KEYS_ROW2_DEFAULT)
        prefs.extraKeysRow1 = Prefs.EXTRA_KEYS_ROW1_DEFAULT
        prefs.extraKeysRow2 = Prefs.EXTRA_KEYS_ROW2_DEFAULT
        toast("Extra keys reset", android.widget.Toast.LENGTH_SHORT)
    }

    // --- Crash logs ---

    private fun showCrashLogs() {
        val logs = CrashHandler.logFiles(this)
        if (logs.isEmpty()) {
            toast("No crash logs", android.widget.Toast.LENGTH_SHORT)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Crash logs")
            .setItems(logs.map { it.name }.toTypedArray()) { _, which -> shareCrashLog(logs[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareCrashLog(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share crash log"))
        } catch (e: Exception) {
            toast("Cannot share crash log: ${e.message}")
        }
    }

    // --- Battery optimization ---

    override fun onResume() {
        super.onResume()
        updateBatteryOptimizationLabel(findViewById(R.id.battery_opt_btn))
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isIgnoringBatteryOptimizations(packageName) == true
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
            toast("Already exempt from battery optimization", android.widget.Toast.LENGTH_SHORT)
            return
        }
        val scoped = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        val generic = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (!IntentStarter.startFirstAvailable(this, scoped, generic)) {
            toast("Could not open battery optimization settings", android.widget.Toast.LENGTH_SHORT)
        }
    }
}
