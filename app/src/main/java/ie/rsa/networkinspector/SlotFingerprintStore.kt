package ie.rsa.networkinspector

import android.content.Context

/**
 * Persists which fingerprints (e.g. centre+date+time for a slot, or
 * centre-id+nextAvailability for a centre) have already been notified
 * about, so the same one never re-triggers a notification. Plain
 * SharedPreferences - no Room/DataStore dependency needed for a simple set
 * of strings, and nothing here is a credential or personal detail. Pass a
 * distinct [key] per kind of fingerprint so slot and centre dedup don't mix.
 */
class SlotFingerprintStore(context: Context, private val key: String = "notified_fingerprints") {
    private val prefs = context.getSharedPreferences("rsa_slot_watcher_prefs", Context.MODE_PRIVATE)

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
