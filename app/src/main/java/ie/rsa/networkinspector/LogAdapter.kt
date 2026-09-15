package ie.rsa.networkinspector

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class LogAdapter(private val context: Context) : BaseAdapter() {

    private val items = mutableListOf<LogEntry>()
    private val inflater = LayoutInflater.from(context)

    fun setItems(newItems: List<LogEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): LogEntry = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_log_entry, parent, false)
        val entry = items[position]
        val text = view.findViewById<TextView>(R.id.logEntryText)
        text.text = entry.oneLineSummary()
        text.setTextColor(
            when {
                entry.isFlagged -> Color.parseColor("#C62828")
                entry.source == LogSource.JS_OBSERVER -> Color.parseColor("#1565C0")
                entry.isMainFrame -> Color.parseColor("#2E7D32")
                else -> Color.parseColor("#424242")
            }
        )
        return view
    }
}
