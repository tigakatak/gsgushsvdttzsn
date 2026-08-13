package com.redt.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.redt.BuildConfig
import com.redt.R
import com.redt.distro.DistroInstaller
import com.redt.service.TerminalService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private val installer by lazy { DistroInstaller(applicationContext) }

    companion object {
        private const val REQ_IMPORT_FONT = 2001
        private const val RESTORE_TIMEOUT_SECONDS = 600L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = prefs()

        findViewById<View>(R.id.settings_back_btn).setOnClickListener { finish() }

        val fontSlider = findViewById<SeekBar>(R.id.font_size_slider)
        val wakelockSwitch = findViewById<Switch>(R.id.wakelock_switch)
        val versionInfo = findViewById<TextView>(R.id.version_info)
        val fontSpinner = findViewById<Spinner>(R.id.font_spinner)

        val currentTheme = prefs.getString(Prefs.KEY_THEME, "amoled")
        val themeNames = Prefs.THEME_NAMES
        val themeValues = Prefs.THEME_VALUES
        val themeSpinner = findViewById<Spinner>(R.id.theme_spinner)
        val themeIdx = (themeValues.indexOf(currentTheme)).coerceAtLeast(0)
        themeSpinner.adapter = makeCheckedSpinnerAdapter(themeNames, themeSpinner)
        themeSpinner.setSelection(themeIdx)
        themeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != themeIdx) {
                    prefs.edit().putString("theme", themeValues[pos]).apply()
                    findViewById<TextView>(R.id.customize_theme_btn).visibility =
                        if (themeValues[pos] == "custom") android.view.View.VISIBLE else android.view.View.GONE
                    recreate()
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        findViewById<TextView>(R.id.customize_theme_btn).apply {
            setOnClickListener { showColorPickerDialog(prefs) }
            visibility = if (currentTheme == "custom") android.view.View.VISIBLE else android.view.View.GONE
        }

        val customFonts = customFontFiles().map { it.name }
        val fonts = Prefs.BUILT_IN_FONTS + customFonts.map { "custom:$it" }
        val fontLabels = fonts.map { if (it.startsWith("custom:")) "${fontDisplayName(it.removePrefix("custom:"))} (custom)" else it }
        val currentFont = prefs.getString(Prefs.KEY_FONT, "monospace")
        val fontIdx = (fonts.indexOf(currentFont)).coerceAtLeast(0)
        fontSpinner.adapter = makeCheckedSpinnerAdapter(fontLabels, fontSpinner)
        fontSpinner.setSelection(fontIdx)
        fontSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != fontIdx) {
                    prefs.edit().putString("font", fonts[pos]).apply()
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        findViewById<TextView>(R.id.import_font_btn).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "font/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            try {
                startActivityForResult(intent, REQ_IMPORT_FONT)
            } catch (_: Exception) {
                val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                try {
                    startActivityForResult(fallback, REQ_IMPORT_FONT)
                } catch (_: Exception) {
                    Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
                }
            }
        }

        renderCustomFontList(prefs)

        fontSlider.progress = prefs.getInt(Prefs.KEY_FONT_SIZE, Prefs.FONT_SIZE_DEFAULT)
        wakelockSwitch.isChecked = prefs.getBoolean(Prefs.KEY_WAKELOCK, Prefs.WAKELOCK_DEFAULT)

        fontSlider.setOnSeekBarChangeListener(simpleSeekBarListener { progress ->
            prefs.edit().putInt(Prefs.KEY_FONT_SIZE, progress).apply()
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

        val lockSwitch = findViewById<Switch>(R.id.lock_switch)
        lockSwitch.isChecked = prefs.getBoolean("lock_enabled", false)
        lockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                com.redt.util.AppLock.setupPinDialog(this, prefs) {
                    lockSwitch.isChecked = prefs.getBoolean("lock_enabled", false)
                }
            } else {
                prefs.edit()
                    .putBoolean(Prefs.KEY_LOCK_ENABLED, false)
                    .putString(Prefs.KEY_LOCK_PIN, "")
                    .apply()
            }
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
                    put("theme", prefs.getString("theme", "amoled"))
                    put("custom_bg", prefs.getInt("custom_bg", 0))
                    put("custom_text", prefs.getInt("custom_text", 0))
                    put("custom_primary", prefs.getInt("custom_primary", 0))
                    put("font", prefs.getString("font", "monospace"))
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
                edit.putString("theme", json.optString("theme", "amoled"))
                edit.putInt("custom_bg", json.optInt("custom_bg", 0))
                edit.putInt("custom_text", json.optInt("custom_text", 0))
                edit.putInt("custom_primary", json.optInt("custom_primary", 0))
                edit.putString("font", json.optString("font", "monospace"))
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
                    if (rootfsDir.exists()) {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Overwrite $distroName?")
                            .setMessage("The existing rootfs will be deleted and replaced.")
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
                withContext(Dispatchers.IO) {
                    if (isBackup) {
                        rootfsDir.deleteRecursively()
                        rootfsDir.mkdirs()
                        val pb = ProcessBuilder(
                            "tar", "-xzf", backupFile.absolutePath, "-C", rootfsDir.absolutePath
                        )
                        pb.redirectErrorStream(true)
                        val proc = pb.start()
                        try {
                            val drain = Thread {
                                try { proc.inputStream.use { it.readBytes() } } catch (_: Exception) {}
                            }
                            drain.isDaemon = true
                            drain.start()
                            val finished = proc.waitFor(RESTORE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                            if (!finished) {
                                proc.destroyForcibly()
                                throw RuntimeException("Restore timed out after ${RESTORE_TIMEOUT_SECONDS}s")
                            }
                            drain.join(2000)
                            if (proc.exitValue() != 0) {
                                throw RuntimeException("tar exited with code ${proc.exitValue()}")
                            }
                        } finally {
                            proc.destroy()
                        }
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
                        installer.repairRootfs(rootfsDir)
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
                if (!isFinishing && !isDestroyed) dialog.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    if (isBackup) "Restore error: ${e.message}" else "Install error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showColorPickerDialog(prefs: android.content.SharedPreferences) {
        val colors = intArrayOf(
            prefs.getInt("custom_bg", 0xFF1E1E2E.toInt()),
            prefs.getInt("custom_text", 0xFFCDD6F4.toInt()),
            prefs.getInt("custom_primary", 0xFF89B4FA.toInt())
        )

        val bgPreview = android.widget.TextView(this).apply {
            text = "  Background  "
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }
        val textPreview = android.widget.TextView(this).apply {
            text = "  Text Color  "
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }
        val primaryPreview = android.widget.TextView(this).apply {
            text = "  Primary/Accent  "
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }

        fun updatePreviews() {
            bgPreview.setBackgroundColor(colors[0])
            bgPreview.setTextColor(colors[1])
            textPreview.setBackgroundColor(colors[0])
            textPreview.setTextColor(colors[1])
            primaryPreview.setBackgroundColor(colors[0])
            primaryPreview.setTextColor(colors[2])
        }
        updatePreviews()

        fun makeColorPicker(label: String, colorIndex: Int): android.widget.LinearLayout {
            val initial = colors[colorIndex]
            val r = (initial shr 16) and 0xFF
            val g = (initial shr 8) and 0xFF
            val b = initial and 0xFF
            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val labelTv = android.widget.TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(0xFFCDD6F4.toInt())
            }
            layout.addView(labelTv)

            val sr = SeekBar(this).apply { max = 255; progress = r }
            val sg = SeekBar(this).apply { max = 255; progress = g }
            val sb = SeekBar(this).apply { max = 255; progress = b }
            val rv = android.widget.TextView(this).apply { text = "$r"; setTextColor(0xFFCDD6F4.toInt()); textSize = 12f; layoutParams = android.widget.LinearLayout.LayoutParams(36, -2) }
            val gv = android.widget.TextView(this).apply { text = "$g"; setTextColor(0xFFCDD6F4.toInt()); textSize = 12f; layoutParams = android.widget.LinearLayout.LayoutParams(36, -2) }
            val bv = android.widget.TextView(this).apply { text = "$b"; setTextColor(0xFFCDD6F4.toInt()); textSize = 12f; layoutParams = android.widget.LinearLayout.LayoutParams(36, -2) }

            val update = {
                colors[colorIndex] = 0xFF000000.toInt() or (sr.progress shl 16) or (sg.progress shl 8) or sb.progress
                updatePreviews()
            }

            val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, f: Boolean) {
                    when (s) { sr -> rv.text = "$p"; sg -> gv.text = "$p"; sb -> bv.text = "$p" }
                    update()
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            }
            sr.setOnSeekBarChangeListener(listener)
            sg.setOnSeekBarChangeListener(listener)
            sb.setOnSeekBarChangeListener(listener)

            for ((seek, valTv, name) in listOf(Triple(sr, rv, "R"), Triple(sg, gv, "G"), Triple(sb, bv, "B"))) {
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(android.widget.TextView(this).apply {
                    text = name; setTextColor(0xFFCDD6F4.toInt()); textSize = 12f
                    layoutParams = android.widget.LinearLayout.LayoutParams(24, -2)
                })
                row.addView(seek.apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f) })
                row.addView(valTv)
                layout.addView(row)
            }
            return layout
        }

        val scroll = android.widget.ScrollView(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(bgPreview)
            addView(makeColorPicker("Background", 0))
            addView(textPreview)
            addView(makeColorPicker("Text", 1))
            addView(primaryPreview)
            addView(makeColorPicker("Primary", 2))
        }
        scroll.addView(container)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Custom Theme Colors")
            .setView(scroll)
            .setPositiveButton("Apply") { _, _ ->
                prefs.edit()
                    .putInt("custom_bg", colors[0])
                    .putInt("custom_text", colors[1])
                    .putInt("custom_primary", colors[2])
                    .apply()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun renderCustomFontList(prefs: android.content.SharedPreferences) {
        val container = findViewById<LinearLayout>(R.id.custom_fonts_list)
        container.removeAllViews()
        val files = customFontFiles()
        if (files.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No custom fonts imported"
                setTextColor(mutedTextColor())
                textSize = 12f
                setPadding(4, 8, 4, 8)
            })
            return
        }
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 6, 8, 6)
            }
            row.addView(TextView(this).apply {
                text = "${fontDisplayName(f.name)} (custom)"
                setTextColor(0xFFCDD6F4.toInt())
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "Rename"
                setTextColor(0xFF89B4FA.toInt())
                textSize = 12f
                setPadding(12, 4, 10, 4)
                setOnClickListener { showRenameFontDialog(f) }
            })
            row.addView(TextView(this).apply {
                text = "Delete"
                setTextColor(0xFFFF6B6B.toInt())
                textSize = 12f
                setPadding(12, 4, 4, 4)
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Remove font?")
                        .setMessage("Delete '${fontDisplayName(f.name)}'? If it is the current font, the terminal falls back to monospace.")
                        .setPositiveButton("Delete") { _, _ ->
                            f.delete()
                            if (prefs.getString("font", "monospace") == "custom:${f.name}") {
                                prefs.edit().putString("font", "monospace").apply()
                            }
                            renderCustomFontList(prefs)
                            rebuildFontSpinner(prefs)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            container.addView(row)
        }
    }

    private fun showRenameFontDialog(file: File) {
        val input = EditText(this).apply {
            setText(fontDisplayName(file.name))
            setTextColor(themeColor(R.attr.terminalText, 0xFFCDD6F4.toInt()))
            setHintTextColor(0xFF7F849C.toInt())
            textSize = 14f
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename font")
            .setMessage("New name (extension is kept)")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newName == fontDisplayName(file.name)) return@setPositiveButton
                val ext = file.extension.lowercase().ifEmpty { "ttf" }
                val target = File(file.parentFile, "$newName.$ext")
                if (target.exists()) {
                    Toast.makeText(this, "A font with that name already exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val prefs = prefs()
                if (file.renameTo(target)) {
                    if (prefs.getString("font", "monospace") == "custom:${file.name}") {
                        prefs.edit().putString("font", "custom:${target.name}").apply()
                    }
                    renderCustomFontList(prefs)
                    rebuildFontSpinner(prefs)
                } else {
                    Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun makeCheckedSpinnerAdapter(
        labels: List<String>,
        spinner: Spinner
    ): ArrayAdapter<String> = object : ArrayAdapter<String>(this, R.layout.spinner_item, labels) {
        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val row = layoutInflater.inflate(R.layout.spinner_dropdown_checked, parent, false)
            row.findViewById<TextView>(R.id.dropdown_text).text = labels[position]
            row.findViewById<TextView>(R.id.dropdown_check).visibility =
                if (position == spinner.selectedItemPosition) android.view.View.VISIBLE else android.view.View.GONE
            return row
        }
    }

    private fun rebuildFontSpinner(prefs: android.content.SharedPreferences) {
        val customFonts = customFontFiles().map { it.name }
        val fonts = Prefs.BUILT_IN_FONTS + customFonts.map { "custom:$it" }
        val fontLabels = fonts.map { if (it.startsWith("custom:")) "${fontDisplayName(it.removePrefix("custom:"))} (custom)" else it }
        val spinner = findViewById<Spinner>(R.id.font_spinner)
        val current = prefs.getString(Prefs.KEY_FONT, "monospace")
        val idx = (fonts.indexOf(current)).coerceAtLeast(0)
        spinner.adapter = makeCheckedSpinnerAdapter(fontLabels, spinner)
        spinner.setSelection(idx)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != idx) {
                    prefs.edit().putString("font", fonts[pos]).apply()
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun importFonts(uri: Uri) {
        try {
            val name = uri.lastPathSegment?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() } ?: "font.ttf"
            val cleanName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .takeIf { it.isNotBlank() } ?: "font.ttf"
            val ext = if (cleanName.substringAfterLast('.', "").equals("otf", true)) "otf" else "ttf"
            val base = cleanName.substringBeforeLast('.', cleanName)
            val dir = File(filesDir, "fonts")
            dir.mkdirs()
            var target = File(dir, cleanName)
            var counter = 1
            while (target.exists()) {
                target = File(dir, "${base}_$counter.$ext")
                counter++
            }
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_FONT && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            for (uri in uris) importFonts(uri)
            val prefs = prefs()
            renderCustomFontList(prefs)
            rebuildFontSpinner(prefs)
            Toast.makeText(this, if (uris.size > 1) "${uris.size} fonts imported" else "Font imported", Toast.LENGTH_SHORT).show()
        }
    }

}
