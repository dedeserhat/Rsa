package ie.rsa.networkinspector

import android.content.Context

/**
 * Persists which test centres the user wants to watch (by name - the only field
 * common to both the centre-list and slot responses) and whether notifications
 * are enabled. Plain SharedPreferences, same as [SlotFingerprintStore]. An empty
 * selection means "no filter, watch every centre" - this is the default/initial
 * state and also what the "ALL CENTRES" button resets to.
 */
class SelectedCentresStore(context: Context) {
    private val prefs = context.getSharedPreferences("rsa_slot_watcher_prefs", Context.MODE_PRIVATE)
    private val centresKey = "selected_centre_names"
    private val notificationsKey = "notifications_enabled"

    private fun selectedNames(): Set<String> = prefs.getStringSet(centresKey, emptySet()) ?: emptySet()

    fun isFilterActive(): Boolean = selectedNames().isNotEmpty()

    fun isSelected(name: String): Boolean {
        val selected = selectedNames()
        return selected.isEmpty() || selected.any { it.equals(name, ignoreCase = true) }
    }

    /** Toggles [name] on/off. [allKnownNames] is needed so unchecking one centre while
     *  "all" (the empty-set state) is active turns into an explicit "all except this one". */
    fun toggle(name: String, allKnownNames: List<String>) {
        val current = if (selectedNames().isEmpty()) allKnownNames.toMutableSet() else selectedNames().toMutableSet()
        if (current.any { it.equals(name, ignoreCase = true) }) {
            current.removeAll { it.equals(name, ignoreCase = true) }
        } else {
            current.add(name)
        }
        prefs.edit().putStringSet(centresKey, current).apply()
    }

    fun selectAll() {
        prefs.edit().putStringSet(centresKey, emptySet()).apply()
    }

    fun notificationsEnabled(): Boolean = prefs.getBoolean(notificationsKey, true)

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(notificationsKey, enabled).apply()
    }
}
