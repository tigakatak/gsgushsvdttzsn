package com.redt.util

import android.content.SharedPreferences
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.redt.ui.Prefs
import com.redt.ui.hintColor
import java.security.MessageDigest

object AppLock {
    @Volatile
    private var unlockedAt = 0L

    fun isUnlocked(prefs: SharedPreferences): Boolean {
        if (!prefs.getBoolean(Prefs.KEY_LOCK_ENABLED, false)) return true
        return System.currentTimeMillis() - unlockedAt < Prefs.UNLOCK_WINDOW_MS
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update((salt + pin).toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getSalt(prefs: SharedPreferences): String {
        var salt = prefs.getString("lock_salt", "") ?: ""
        if (salt.isEmpty()) {
            salt = java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("lock_salt", salt).apply()
        }
        return salt
    }

    private fun verifyPin(input: String, prefs: SharedPreferences): Boolean {
        val stored = prefs.getString(Prefs.KEY_LOCK_PIN, "") ?: ""
        if (stored.isEmpty()) return false
        val salt = getSalt(prefs)
        val hashed = hashPin(input, salt)
        return MessageDigest.isEqual(hashed.toByteArray(), stored.toByteArray())
    }

    fun requireUnlock(
        activity: AppCompatActivity,
        prefs: SharedPreferences,
        onDone: () -> Unit
    ) {
        if (isUnlocked(prefs)) {
            onDone()
            return
        }
        showUnlockDialog(activity, prefs, onDone)
    }

    private fun showUnlockDialog(
        activity: AppCompatActivity,
        prefs: SharedPreferences,
        onDone: () -> Unit
    ) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter PIN"
            setTextColor(0xFFCDD6F4.toInt())
            setHintTextColor(activity.hintColor())
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("App locked")
            .setMessage("Enter your PIN to unlock RedT")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel") { _, _ -> activity.finish() }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (verifyPin(input.text.toString(), prefs)) {
                unlockedAt = System.currentTimeMillis()
                dialog.dismiss()
                onDone()
            } else {
                Toast.makeText(activity, "Wrong PIN", Toast.LENGTH_SHORT).show()
                input.text.clear()
            }
        }
    }

    fun setupPinDialog(
        activity: AppCompatActivity,
        prefs: SharedPreferences,
        onSaved: () -> Unit
    ) {
        val pin1 = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "New PIN (4-8 digits)"
            setTextColor(0xFFCDD6F4.toInt())
            setHintTextColor(activity.hintColor())
        }
        val pin2 = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Confirm PIN"
            setTextColor(0xFFCDD6F4.toInt())
            setHintTextColor(activity.hintColor())
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val error = TextView(activity).apply {
            setTextColor(0xFFFF6B6B.toInt())
            textSize = 13f
            visibility = android.view.View.GONE
        }
        layout.addView(pin1, lp)
        layout.addView(pin2, lp)
        layout.addView(error, lp)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Set app lock PIN")
            .setView(layout)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { _, _ ->
                prefs.edit().putBoolean(Prefs.KEY_LOCK_ENABLED, false).apply()
                onSaved()
            }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p1 = pin1.text.toString()
            val p2 = pin2.text.toString()
            when {
                p1.length < 4 || p1.length > 8 -> {
                    error.text = "PIN must be 4-8 digits"
                    error.visibility = android.view.View.VISIBLE
                }
                p1 != p2 -> {
                    error.text = "PINs do not match"
                    error.visibility = android.view.View.VISIBLE
                }
                else -> {
                    val salt = getSalt(prefs)
                    prefs.edit()
                        .putBoolean(Prefs.KEY_LOCK_ENABLED, true)
                        .putString(Prefs.KEY_LOCK_PIN, hashPin(p1, salt))
                        .apply()
                    Toast.makeText(activity, "App lock enabled", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onSaved()
                }
            }
        }
    }
}
