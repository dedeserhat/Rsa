package ie.rsa.networkinspector

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.TextView

/** Shows [TestCentreStatus] summaries (name + nextAvailability) with a per-centre
 *  checkbox backed by [SelectedCentresStore], for the CENTRES tab's multi-select. */
class CentreAdapter(
    private val context: Context,
    private val store: SelectedCentresStore,
    private val onSelectionChanged: () -> Unit
) : BaseAdapter() {

    private val items = mutableListOf<TestCentreStatus>()
    private val inflater = LayoutInflater.from(context)

    fun setItems(newItems: List<TestCentreStatus>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): TestCentreStatus = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_centre_card, parent, false)
        val entry = items[position]
        val text = view.findViewById<TextView>(R.id.centreCardText)
        val checkbox = view.findViewById<CheckBox>(R.id.centreCheckbox)

        text.text = entry.displayText()
        text.setTextColor(if (entry.hasGenuineDate) Color.parseColor("#1B5E20") else Color.parseColor("#616161"))

        checkbox.setOnCheckedChangeListener(null)
        checkbox.isChecked = store.isSelected(entry.name)
        checkbox.setOnCheckedChangeListener { _, _ ->
            store.toggle(entry.name, items.map { it.name })
            onSelectionChanged()
        }
        return view
    }
}
