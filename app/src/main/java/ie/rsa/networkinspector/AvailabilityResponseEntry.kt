package ie.rsa.networkinspector

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

enum class ParserStatus { NOT_APPLICABLE, PARSED, UNRECOGNIZED_FORMAT }

/** A single generically-parsed driving test slot - never invented, only extracted from a real response. */
data class DrivingTestSlot(val centre: String, val date: String, val time: String) {
    /** centre+date+time, normalized - used to avoid re-notifying about the same slot. */
    val fingerprint: String by lazy {
        (centre + "|" + date + "|" + time).toLowerCase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '|' }
    }

    fun displayText(): String = "$centre\n$date\n$time"
}

/**
 * A response captured from one of the RSA availability/work-order/timer
 * endpoints only. Never built from, or containing, headers, cookies, or
 * auth tokens - just what the observer script (or WebView error callback)
 * reported, already deep-redacted (see [redactSensitiveJson]) before this
 * object is ever constructed.
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
    val isWorkOrderResponse: Boolean = url.contains(WORK_ORDER_MARKER, ignoreCase = true) && !url.contains("Availability", ignoreCase = true)

    /** Any endpoint that plausibly returns a list of bookable slots - not just the literally-named one. */
    val isSlotListResponse: Boolean =
        isSlotsResponse ||
            url.contains(ALL_AVAILABILITY_MARKER, ignoreCase = true) ||
            url.contains(BY_WORKORDER_TERRITORY_MARKER, ignoreCase = true) ||
            isClosestWorkOrderResponse

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

    /** Non-null only for slot-list-shaped responses that parse as JSON. */
    val slotCount: Int? by lazy {
        if (!isSlotListResponse) return@lazy null
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
        if (!isSlotListResponse) return@lazy emptyList<String>()
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

    private val topLevelJson: Any? by lazy {
        runCatching {
            val trimmed = rawBody.trim()
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed)
                else -> null
            }
        }.getOrNull()
    }

    private fun collectArraysAnywhere(node: Any, depth: Int, acc: MutableList<JSONArray>) {
        if (depth > 5) return
        when (node) {
            is JSONArray -> {
                acc.add(node)
                for (i in 0 until node.length()) {
                    val v = node.opt(i)
                    if (v is JSONObject || v is JSONArray) collectArraysAnywhere(v, depth + 1, acc)
                }
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = node.opt(keys.next())
                    if (v is JSONObject || v is JSONArray) collectArraysAnywhere(v, depth + 1, acc)
                }
            }
        }
    }

    private val candidateSlotArrays: List<JSONArray> by lazy {
        val acc = mutableListOf<JSONArray>()
        topLevelJson?.let { collectArraysAnywhere(it, 0, acc) }
        acc
    }

    /**
     * PARSED means we found at least one array in the response to look at (even if it
     * turns out to genuinely contain zero slots - that's a real "no slots" result).
     * UNRECOGNIZED_FORMAT means the body wasn't parseable JSON or had no array-shaped
     * data anywhere, so an empty [parsedSlots] must NOT be read as "no availability".
     */
    val parserStatus: ParserStatus by lazy {
        when {
            !isSlotListResponse -> ParserStatus.NOT_APPLICABLE
            topLevelJson == null -> ParserStatus.UNRECOGNIZED_FORMAT
            candidateSlotArrays.isEmpty() -> ParserStatus.UNRECOGNIZED_FORMAT
            else -> ParserStatus.PARSED
        }
    }

    private fun bestFieldMatch(obj: JSONObject, orderedHints: List<String>): String? {
        val keys = mutableListOf<String>()
        val it = obj.keys()
        while (it.hasNext()) keys.add(it.next())
        for (hint in orderedHints) {
            for (key in keys) {
                if (key.toLowerCase(Locale.ROOT).contains(hint)) {
                    val v = obj.opt(key)
                    if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) {
                        val s = v.toString().trim()
                        if (s.isNotEmpty()) return s
                    }
                }
            }
        }
        return null
    }

    private fun splitIsoDateTime(value: String): Pair<String, String>? {
        val match = Regex("""(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})""").find(value) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    /**
     * Generically-extracted slots (test centre / date / time), never hard-coded to an
     * assumed schema: every candidate array in the response is scanned for objects
     * whose keys plausibly contain a centre name plus a date and a time (either as
     * separate fields or one combined date-time field). An element that doesn't
     * confidently yield all three is skipped, never guessed at.
     */
    val parsedSlots: List<DrivingTestSlot> by lazy {
        if (parserStatus != ParserStatus.PARSED) return@lazy emptyList<DrivingTestSlot>()
        val best = candidateSlotArrays.maxBy { arr -> (0 until arr.length()).count { arr.opt(it) is JSONObject } }
            ?: return@lazy emptyList<DrivingTestSlot>()
        val slots = mutableListOf<DrivingTestSlot>()
        for (i in 0 until best.length()) {
            val obj = best.optJSONObject(i) ?: continue
            val centre = bestFieldMatch(obj, CENTRE_FIELD_HINTS)
            var date = bestFieldMatch(obj, DATE_FIELD_HINTS)
            var time = bestFieldMatch(obj, TIME_FIELD_HINTS)
            if (date == null || time == null) {
                bestFieldMatch(obj, DATETIME_FIELD_HINTS)?.let { combined ->
                    splitIsoDateTime(combined)?.let { (d, t) ->
                        if (date == null) date = d
                        if (time == null) time = t
                    }
                }
            }
            val finalDate = date
            val finalTime = time
            if (centre != null && finalDate != null && finalTime != null) {
                slots.add(DrivingTestSlot(centre, finalDate, finalTime))
            }
        }
        slots
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
        if (isSlotListResponse) {
            when (parserStatus) {
                ParserStatus.PARSED -> {
                    slotCount?.let { sb.append("SLOTS FOUND: ").append(it).append('\n') }
                    if (detectedFields.isNotEmpty()) {
                        sb.append("Detected fields: ").append(detectedFields.joinToString(", ")).append('\n')
                    }
                }
                ParserStatus.UNRECOGNIZED_FORMAT ->
                    sb.append("RSA response format changed — parser update required\n")
                ParserStatus.NOT_APPLICABLE -> {}
            }
            sb.append('\n')
        }
        sb.append("Response:\n").append(prettyBody)
        return sb.toString()
    }
}
