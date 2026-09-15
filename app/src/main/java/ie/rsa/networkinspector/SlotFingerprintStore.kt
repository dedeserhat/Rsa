package ie.rsa.networkinspector

import android.content.Context

/**
 * Persists which slot fingerprints (centre+date+time) have already been
 * notified about, so the same slot never re-triggers a notification. Plain
 * SharedPreferences - no Room/DataStore dependency needed for a simple set
 * of strings, and nothing here is a credential or personal detail.
 */
class SlotFingerprintStore(context: Context) {
    private val prefs = context.getSharedPreferences("rsa_slot_watcher_prefs", Context.MODE_PRIVATE)
    private val key = "notified_fingerprints"

    fun hasNotified(fingerprint: String): Boolean = fingerprints().contains(fingerprint)

    fun markNotified(fingerprint: String) {
        val updated = fingerprints().toMutableSet()
        updated.add(fingerprint)
        prefs.edit().putStringSet(key, updated).apply()
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }

    private fun fingerprints(): Set<String> = prefs.getStringSet(key, emptySet()) ?: emptySet()
}
