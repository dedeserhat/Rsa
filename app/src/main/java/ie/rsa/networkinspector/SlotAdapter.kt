package ie.rsa.networkinspector

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/** Shows generically-parsed [DrivingTestSlot]s as "AVAILABLE DRIVING TESTS" cards. */
class SlotAdapter(private val context: Context) : BaseAdapter() {

    private val items = mutableListOf<DrivingTestSlot>()
    private val inflater = LayoutInflater.from(context)

    fun setItems(newItems: List<DrivingTestSlot>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): DrivingTestSlot = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_slot_card, parent, false)
        view.findViewById<TextView>(R.id.slotCardText).text = items[position].displayText()
        return view
    }
}
