package alpiner.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import alpiner.app.R

/** Two swipeable pages under the terminal: the extra-keys rows and a line-input field. */
class ExtraKeysPagerAdapter(
    private val onKeysPage: (row1: LinearLayout, row2: LinearLayout) -> Unit,
    private val onSendLine: (String) -> Unit,
) : RecyclerView.Adapter<ExtraKeysPagerAdapter.PageHolder>() {

    class PageHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemCount(): Int = 2

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == PAGE_KEYS) {
            val view = inflater.inflate(R.layout.extra_keys_page, parent, false)
            onKeysPage(view.findViewById(R.id.extra_keys_row1), view.findViewById(R.id.extra_keys_row2))
            PageHolder(view)
        } else {
            val input = inflater.inflate(R.layout.extra_keys_input, parent, false) as EditText
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
                onSendLine(input.text.toString())
                input.setText("")
                true
            }
            PageHolder(input)
        }
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {}

    private companion object {
        const val PAGE_KEYS = 0
    }
}
