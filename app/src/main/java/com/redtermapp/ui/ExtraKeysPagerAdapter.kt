package com.redtermapp.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.redtermapp.R

class ExtraKeysPagerAdapter(
    private val activity: TerminalActivity,
    private val onKeysPageReady: (LinearLayout, LinearLayout) -> Unit,
    private val onInputPageReady: (EditText) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_KEYS = 0
        const val TYPE_INPUT = 1
    }

    override fun getItemViewType(position: Int): Int =
        if (position == 0) TYPE_KEYS else TYPE_INPUT

    override fun getItemCount(): Int = 2

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_KEYS -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.extra_keys_page, parent, false)
                val row1 = view.findViewById<LinearLayout>(R.id.extra_keys_container)
                val row2 = view.findViewById<LinearLayout>(R.id.extra_keys_container_row2)
                onKeysPageReady(row1, row2)
                KeysViewHolder(view)
            }
            else -> {
                val editText = EditText(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    maxLines = 1
                    isSingleLine = true
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    background = null
                    setPadding(
                        activity.dp(12), 0,
                        activity.dp(12), 0
                    )
                    gravity = Gravity.CENTER_VERTICAL
                    hint = "Type and press Enter to send"
                    setTextColor(activity.themeColor(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                    setHintTextColor(activity.hintColor())
                    textSize = 14f
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            activity.sendInputLine(text.toString())
                            setText("")
                            true
                        } else false
                    }
                }
                onInputPageReady(editText)
                InputViewHolder(editText)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}

    class KeysViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view)
    class InputViewHolder(view: EditText) : RecyclerView.ViewHolder(view)
}
