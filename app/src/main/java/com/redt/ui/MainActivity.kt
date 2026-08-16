package com.redt.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.redt.R
import com.redt.distro.DistroInstaller
import com.redt.session.terminalSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val installer by lazy { DistroInstaller(applicationContext) }

    private var lastAppliedTheme: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        lastAppliedTheme = prefs().getString(Prefs.KEY_THEME, "amoled")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Exit from the notification quits the app entirely: this collector is
        // unconditional (not lifecycle-repeatable) so it also fires while the
        // activity is stopped in the background. finishAffinity() closes every
        // activity in the task, including Settings stacked on top of this one.
        lifecycleScope.launch {
            sessionStore.exitSignal.collect {
                finishAffinity()
            }
        }

        populateDistroList()
        findViewById<ImageButton>(R.id.main_back_btn).setOnClickListener { finish() }

        val onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.settings_button).setOnClickListener { onSettings() }
        findViewById<ImageButton>(R.id.settings_gear).setOnClickListener { onSettings() }

        findViewById<Button>(R.id.new_session_button).setOnClickListener {
            val distros = installer.getInstalledDistros()
            when (distros.size) {
                0 -> Toast.makeText(this, "No distros installed. Add one first.", Toast.LENGTH_SHORT).show()
                1 -> TerminalActivity.launch(this, distros.first())
                else -> {
                    val names = distros.map { it.capitalized() }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Select distro")
                        .setItems(names) { _, which ->
                            TerminalActivity.launch(this, distros[which])
                        }
                        .show()
                }
            }
        }

        findViewById<Button>(R.id.add_distro_button).setOnClickListener {
            startActivity(Intent(this, WelcomeActivity::class.java).apply {
                putExtra(WelcomeActivity.EXTRA_SELECT_ONLY, true)
            })
        }

        // Clean up disk usage not owned by any visible feature: rootfs husks
        // from interrupted uninstalls and stale download files.
        lifecycleScope.launch(Dispatchers.IO) {
            installer.sweepOrphanFiles()
        }

        AppTheme.recolorCustomChrome(this)
    }

    private fun populateDistroList() {
        val container = findViewById<LinearLayout>(R.id.distro_list)
        container.removeAllViews()

        val installed = installer.getInstalledDistros()
        if (installed.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No distributions installed.\nTap + to install one."
                setTextColor(mutedTextColor())
                textSize = 16f
                setPadding(16, 16, 16, 16)
            })
            return
        }

        for (name in installed) {
            val rootfsDir = installer.getRootfsDir(name)
            val sizeLabel = DistroUi.buildDistroSizeLabel(this, rootfsDir, 12f)
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
                setCardBackgroundColor(themeColor(R.attr.extraKeysBg, 0xFF181825.toInt()))
                radius = 12f
                setOnClickListener {
                    TerminalActivity.launch(this@MainActivity, name)
                }
                setOnLongClickListener {
                    showDistroMenu(name)
                    true
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(24, 24, 24, 24)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        addView(TextView(context).apply {
                            text = name.capitalized()
                            setTextColor(themeColor(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                            textSize = 18f
                        })
                        addView(sizeLabel)
                    })
                    addView(TextView(context).apply {
                        text = "Launch ›"
                        setTextColor(0xFF89B4FA.toInt())
                        textSize = 16f
                    })
                })
            }
            container.addView(card)
        }
    }

    private fun showDistroMenu(name: String) {
        val items = arrayOf("Launch", "Backup now", "Home shortcut", "Reset to default", "Remove")
        AlertDialog.Builder(this)
            .setTitle(name.capitalized())
            .setItems(items) { _, which ->
                when (items[which]) {
                    "Launch" -> TerminalActivity.launch(this, name)
                    "Backup now" -> backupDistro(name)
                    "Home shortcut" -> createHomeShortcut(name)
                    "Reset to default" -> resetDistro(name)
                    "Remove" -> confirmDelete(name)
                }
            }
            .show()
    }

    private fun createHomeShortcut(name: String) {
        val intent = Intent(this, TerminalActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(TerminalActivity.EXTRA_DISTRO, name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "launch_$name")
            .setShortLabel(name.capitalized())
            .setLongLabel("Open $name in RedT")
            .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(this, info, null)
        Toast.makeText(this, "Pin the shortcut from the system dialog", Toast.LENGTH_LONG).show()
    }

    private fun resetDistro(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Reset $name to default?")
            .setMessage("Wipes installed packages, caches and shell configs, restoring the freshly extracted base. The next launch will run first-time setup again.")
            .setPositiveButton("Reset") { _, _ ->
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Resetting $name")
                    .setMessage("Restoring base files (downloads the base image if needed)...")
                    .setCancelable(false)
                    .show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = try {
                        installer.resetToDefault(name) { }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Activity teardown: let the coroutine die quietly instead
                        // of reporting a spurious failure toast.
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) dialog.dismiss()
                        if (ok) {
                            Toast.makeText(this@MainActivity, "$name reset - next launch runs setup again", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Reset failed. Check network and try again.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun backupDistro(name: String) {
        Toast.makeText(this, "Backing up $name...", Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val outDir = DistroUi.backupDir(appContext)
                val ok = installer.backup(name, outDir)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (ok) {
                        Toast.makeText(
                            this@MainActivity,
                            "Backup saved: ${java.io.File(outDir, "${name}_backup.tar.gz").absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Backup failed", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    Toast.makeText(this@MainActivity, "Backup error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmDelete(name: String) {
        DistroUi.confirmDelete(this, installer, name) { populateDistroList() }
    }

    override fun onResume() {
        super.onResume()
        val current = prefs().getString(Prefs.KEY_THEME, "amoled")
        if (lastAppliedTheme != null && lastAppliedTheme != current) {
            // Theme changed while this activity was stopped (e.g. in Settings):
            // setTheme() cannot restyle existing views, rebuild instead.
            lastAppliedTheme = current
            if (!isFinishing && !isDestroyed) recreate()
            return
        }
        lastAppliedTheme = current
        com.redt.util.AppLock.requireUnlock(this, prefs()) { populateDistroList() }
    }
}