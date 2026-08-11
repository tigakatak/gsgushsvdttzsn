package com.redtermapp.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller

class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_config)

        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val distros = DistroInstaller(applicationContext).getInstalledDistros()
        val title = findViewById<TextView>(R.id.widget_config_title)
        val list = findViewById<ListView>(R.id.widget_config_list)
        val prefs = prefs()
        val current = prefs.getString("widget_distro_$widgetId", null)

        if (distros.isEmpty()) {
            android.widget.Toast.makeText(this, "No distros installed. Open RedTerm to install one.", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }

        title.text = "Pick a distro for this widget"
        val names = distros.map { it.capitalized() }.toTypedArray()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        list.setOnItemClickListener { _, _, pos, _ ->
            prefs.edit().putString("widget_distro_$widgetId", distros[pos]).apply()
            val manager = AppWidgetManager.getInstance(this)
            RedTermWidgetProvider().updateWidget(this, manager, widgetId)
            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            setResult(RESULT_OK, result)
            finish()
        }
        if (current != null) {
            val idx = distros.indexOf(current)
            if (idx >= 0) list.setItemChecked(idx, true)
        }
    }
}
