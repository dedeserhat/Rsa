package ie.rsa.networkinspector

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A response captured from one of the RSA availability/timer endpoints only.
 * Never built from, or containing, headers, cookies, or auth tokens - just
 * what the observer script (or WebView error callback) reported.
 */
data class AvailabilityResponseEntry(
    val timestamp: Long,
    val method: String,
    val url: String,
    val status: Int,
    val contentType: String,
    val rawBody: String,
    val source: LogSource
) {
    val sanitizedUrl: String by lazy { sanitizeUrlForDisplay(url) }

    val isTimerResponse: Boolean = url.contains(TIMER_MARKER, ignoreCase = true)
    val isSlotsResponse: Boolean = url.contains(SLOTS_MARKER, ignoreCase = true)
    val isClosestWorkOrderResponse: Boolean = url.contains(CLOSEST_WORKORDER_MARKER, ignoreCase = true)

    private val looksJson: Boolean by lazy {
        contentType.contains("json", ignoreCase = true) ||
            rawBody.trim().let { it.startsWith("{") || it.startsWith("[") }
    }

    /** Pretty-printed JSON when possible, otherwise the raw text, truncated for display/export. */
    val prettyBody: String by lazy {
        val trimmed = rawBody.trim()
        val pretty = if (looksJson) {
            runCatching {
                when {
                    trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                    trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                    else -> rawBody
                }
            }.getOrDefault(rawBody)
        } else {
            rawBody
        }
        if (pretty.length > 20_000) pretty.substring(0, 20_000) + "\n… (truncated)" else pretty
    }

    private fun firstArrayField(obj: JSONObject): JSONArray? {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val value = obj.opt(keys.next())
            if (value is JSONArray) return value
        }
        return null
    }

    /** Non-null only for Availability/slots responses that parse as JSON. */
    val slotCount: Int? by lazy {
        if (!isSlotsResponse) return@lazy null
        runCatching {
            val trimmed = rawBody.trim()
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed).length()
                trimmed.startsWith("{") -> firstArrayField(JSONObject(trimmed))?.length()
                else -> null
            }
        }.getOrNull()
    }

    /** Field-name labels (date, slot id, test centre, ...) detected in a sample slot object. */
    val detectedFields: List<String> by lazy {
        if (!isSlotsResponse) return@lazy emptyList<String>()
        runCatching {
            val trimmed = rawBody.trim()
            val sample = when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    if (arr.length() > 0) arr.optJSONObject(0) else null
                }
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    val arr = firstArrayField(obj)
                    if (arr != null && arr.length() > 0) arr.optJSONObject(0) else obj
                }
                else -> null
            } ?: return@runCatching emptyList<String>()

            val keys = mutableListOf<String>()
            val keyIterator = sample.keys()
            while (keyIterator.hasNext()) keys.add(keyIterator.next())

            SLOT_FIELD_HINTS.entries
                .filter { (_, hints) -> keys.any { key -> hints.any { hint -> key.toLowerCase(Locale.ROOT).contains(hint) } } }
                .map { it.key }
        }.getOrDefault(emptyList())
    }

    /** Raw server-returned timer value - never treated as a polling instruction. */
    val timerValueSeconds: String? by lazy {
        if (!isTimerResponse) return@lazy null
        runCatching {
            val trimmed = rawBody.trim()
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    val candidateKeys = listOf("value", "Value", "seconds", "timerSeconds", "SlotsAvailableTimerInSeconds", "result")
                    var found: String? = null
                    for (key in candidateKeys) {
                        if (obj.has(key)) {
                            found = obj.get(key).toString()
                            break
                        }
                    }
                    found ?: obj.toString()
                }
                trimmed.toDoubleOrNull() != null -> trimmed
                else -> trimmed.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    fun formattedTime(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(timestamp)

    /** One card's worth of text for the AVAILABILITY tab. */
    fun cardSummary(): String {
        val sb = StringBuilder()
        sb.append(method).append('\n')
        sb.append(status).append('\n').append('\n')
        sb.append(sanitizedUrl).append('\n').append('\n')
        sb.append("Time: ").append(formattedTime()).append('\n').append('\n')

        if (isTimerResponse) {
            timerValueSeconds?.let { sb.append("RSA SLOT TIMER VALUE: ").append(it).append("\n\n") }
        }
        if (isSlotsResponse) {
            slotCount?.let { sb.append("SLOTS FOUND: ").append(it).append('\n') }
            if (detectedFields.isNotEmpty()) {
                sb.append("Detected fields: ").append(detectedFields.joinToString(", ")).append('\n')
            }
            sb.append('\n')
        }
        sb.append("Response:\n").append(prettyBody)
        return sb.toString()
    }
}
