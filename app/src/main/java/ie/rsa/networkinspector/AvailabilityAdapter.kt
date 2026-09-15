package ie.rsa.networkinspector

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class AvailabilityAdapter(private val context: Context) : BaseAdapter() {

    private val items = mutableListOf<AvailabilityResponseEntry>()
    private val inflater = LayoutInflater.from(context)

    fun setItems(newItems: List<AvailabilityResponseEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): AvailabilityResponseEntry = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_availability_card, parent, false)
        val entry = items[position]
        val text = view.findViewById<TextView>(R.id.availabilityCardText)
        text.text = entry.cardSummary()
        text.setTextColor(
            when {
                entry.status in RESTRICTION_HTTP_CODES -> Color.parseColor("#C62828")
                entry.status in 200..299 -> Color.parseColor("#212121")
                else -> Color.parseColor("#EF6C00")
            }
        )
        return view
    }
}
