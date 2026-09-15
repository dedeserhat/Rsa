package ie.rsa.networkinspector

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

enum class ParserStatus { NOT_APPLICABLE, PARSED, UNRECOGNIZED_FORMAT }

/** Field-name substrings that identify a "next availability summary" field on a
 *  CENTRE object - never a real per-appointment date, so slot extraction must
 *  never treat a match here as a slot date/time. */
private const val NEXT_AVAILABILITY_HINT = "nextavailability"

/** Sentinel/empty values RSA uses for "this centre has no known next date". */
private fun isSentinelOrEmptyDate(value: String?): Boolean {
    if (value.isNullOrBlank()) return true
    val v = value.trim()
    return v.startsWith("0001-01-01") || v.equals("null", ignoreCase = true)
}

/** A single generically-parsed driving test slot - never invented, only extracted from a real response. */
data class DrivingTestSlot(val centre: String, val date: String, val time: String) {
    /** centre+date+time, normalized - used to avoid re-notifying about the same slot. */
    val fingerprint: String by lazy {
        (centre + "|" + date + "|" + time).toLowerCase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '|' }
    }

    fun displayText(): String = "$centre\n$date\n$time"
}

/**
 * A test centre's overall next-availability summary (NOT an individual
 * appointment) - e.g. from Availability/ByWorkOrderAndTerritory or
 * Availability/ClosestSimpleByWorkOrder. [hasGenuineDate] is false for the
 * "0001-01-01T00:00:00Z" / null / empty sentinel RSA uses for "no date".
 */
data class TestCentreStatus(val id: String, val name: String, val nextAvailability: String?, val hasGenuineDate: Boolean) {
    val fingerprint: String by lazy {
        (id + "|" + (nextAvailability ?: "")).toLowerCase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '|' }
    }

    fun displayText(): String =
        if (hasGenuineDate) "$name\n${nextAvailability}" else "$name\nNO DATE AVAILABLE"
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
    val isByWorkOrderTerritoryResponse: Boolean = url.contains(BY_WORKORDER_TERRITORY_MARKER, ignoreCase = true)
    val isAllAvailabilityResponse: Boolean =
        url.contains(ALL_AVAILABILITY_MARKER, ignoreCase = true) && !isByWorkOrderTerritoryResponse
    val isWorkOrderResponse: Boolean = url.contains(WORK_ORDER_MARKER, ignoreCase = true) && !url.contains("Availability", ignoreCase = true)

    /** Any /api/v1/Availability endpoint response (every one of them is captured - see AVAILABILITY_URL_MARKERS). */
    val isAvailabilityFamilyResponse: Boolean =
        url.contains("Availability", ignoreCase = true) && !isWorkOrderResponse

    /** For the DEBUG tab's "group by endpoint" breakdown. Only meaningful when [isAvailabilityFamilyResponse]. */
    val endpointGroup: String by lazy {
        when {
            isByWorkOrderTerritoryResponse -> "ByWorkOrderAndTerritory"
            isClosestWorkOrderResponse -> "ClosestSimpleByWorkOrder"
            isSlotsResponse -> "slots"
            isAllAvailabilityResponse -> "All"
            isAvailabilityFamilyResponse -> "Other"
            else -> "-"
        }
    }

    /** Kept for backward compatibility with UI code that gates on "this is an
     *  Availability list-shaped response worth parsing" - now broader than
     *  just the literally-named endpoints, since ANY Availability endpoint response
     *  might turn out to hold either a centre list or a genuine slot list. */
    val isSlotListResponse: Boolean = isAvailabilityFamilyResponse

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

    /** Every JSON array found anywhere in the response - not yet classified as centres vs slots. */
    private val candidateArrays: List<JSONArray> by lazy {
        val acc = mutableListOf<JSONArray>()
        topLevelJson?.let { collectArraysAnywhere(it, 0, acc) }
        acc
    }

    private fun normalizedKeys(obj: JSONObject): Set<String> {
        val keys = mutableSetOf<String>()
        val it = obj.keys()
        while (it.hasNext()) keys.add(it.next().toLowerCase(Locale.ROOT))
        return keys
    }

    /**
     * A TEST CENTRE object per the real RSA schema discovered via
     * Availability/ByWorkOrderAndTerritory: id, name, county, territoryId,
     * latitude, longitude, nextAvailability, providesRequestedServices,
     * isClosest. Requires an identity (id+name), a next-availability summary
     * field, and at least one centre-metadata field - specific enough not to
     * misclassify a genuine per-appointment slot object.
     */
    private fun looksLikeCentreObject(obj: JSONObject): Boolean {
        val keys = normalizedKeys(obj)
        val hasIdentity = keys.contains("id") && keys.contains("name")
        val hasNextAvailability = keys.any { it.contains(NEXT_AVAILABILITY_HINT) }
        val centreMetaKeys = setOf("county", "territoryid", "latitude", "longitude", "providesrequestedservices", "isclosest")
        val hasCentreMeta = keys.any { it in centreMetaKeys }
        return hasIdentity && hasNextAvailability && hasCentreMeta
    }

    private fun arrayLooksLikeCentreList(arr: JSONArray): Boolean {
        var centreLike = 0
        var total = 0
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            total++
            if (looksLikeCentreObject(obj)) centreLike++
        }
        return total > 0 && centreLike * 2 >= total // majority vote
    }

    /** Arrays classified as centre lists (by URL OR by object shape) - never counted as slots. */
    private val centreArrays: List<JSONArray> by lazy {
        candidateArrays.filter { arrayLooksLikeCentreList(it) }
    }

    /** Arrays NOT classified as centre lists - the only place real appointment slots can come from. */
    private val nonCentreArrays: List<JSONArray> by lazy {
        candidateArrays.filterNot { arrayLooksLikeCentreList(it) }
    }

    /** True for ByWorkOrderAndTerritory/ClosestSimpleByWorkOrder, or any other endpoint whose
     *  response objects match the real centre-object shape - "similar centre-list endpoints". */
    val isCentreListResponse: Boolean by lazy {
        isByWorkOrderTerritoryResponse || isClosestWorkOrderResponse || centreArrays.isNotEmpty()
    }

    private fun findKeyContaining(obj: JSONObject, hint: String): String? {
        val it = obj.keys()
        while (it.hasNext()) {
            val key = it.next()
            if (key.toLowerCase(Locale.ROOT).contains(hint)) {
                val v = obj.opt(key)
                if (v != null && v != JSONObject.NULL) return v.toString()
            }
        }
        return null
    }

    private fun extractCentre(obj: JSONObject): TestCentreStatus? {
        val id = obj.opt("id")?.takeIf { it != JSONObject.NULL }?.toString() ?: return null
        val name = obj.opt("name")?.takeIf { it != JSONObject.NULL }?.toString() ?: return null
        val nextAvailability = findKeyContaining(obj, NEXT_AVAILABILITY_HINT)
        return TestCentreStatus(id, name, nextAvailability, !isSentinelOrEmptyDate(nextAvailability))
    }

    /** Test centres (name + nextAvailability) - never appointment slots. Empty unless
     *  [isCentreListResponse] and the response parsed as JSON. */
    val parsedCentres: List<TestCentreStatus> by lazy {
        if (centreArrays.isEmpty()) return@lazy emptyList<TestCentreStatus>()
        val best = centreArrays.maxBy { arr -> (0 until arr.length()).count { arr.opt(it) is JSONObject } }
            ?: return@lazy emptyList<TestCentreStatus>()
        val centres = mutableListOf<TestCentreStatus>()
        for (i in 0 until best.length()) {
            val obj = best.optJSONObject(i) ?: continue
            extractCentre(obj)?.let { centres.add(it) }
        }
        centres
    }

    /** Non-null only when this response holds slot-list-shaped (non-centre) data that parses as JSON. */
    val slotCount: Int? by lazy {
        if (!isAvailabilityFamilyResponse || isCentreListResponse) return@lazy null
        if (parserStatus != ParserStatus.PARSED) return@lazy null
        parsedSlots.size
    }

    /** Field-name labels (date, slot id, test centre, ...) detected in a sample non-centre object. */
    val detectedFields: List<String> by lazy {
        if (!isAvailabilityFamilyResponse || isCentreListResponse) return@lazy emptyList<String>()
        runCatching {
            val sample = nonCentreArrays.firstOrNull { it.length() > 0 }?.optJSONObject(0)
                ?: return@runCatching emptyList<String>()
            val keys = mutableListOf<String>()
            val keyIterator = sample.keys()
            while (keyIterator.hasNext()) keys.add(keyIterator.next())

            SLOT_FIELD_HINTS.entries
                .filter { (_, hints) -> keys.any { key -> hints.any { hint -> key.toLowerCase(Locale.ROOT).contains(hint) } } }
                .map { it.key }
        }.getOrDefault(emptyList())
    }

    /**
     * PARSED means we found at least one array to look at and could classify it (as a
     * centre list, or as genuinely-parsed slots, or as a confirmed-empty list) - even
     * zero results from a properly-shaped array is a real result, never guessed at.
     * UNRECOGNIZED_FORMAT means the body wasn't parseable JSON, had no array-shaped
     * data anywhere, or had array elements we couldn't classify as either shape - so
     * an empty [parsedSlots]/[parsedCentres] here must NOT be read as "no availability".
     */
    val parserStatus: ParserStatus by lazy {
        if (!isAvailabilityFamilyResponse) return@lazy ParserStatus.NOT_APPLICABLE
        if (topLevelJson == null) return@lazy ParserStatus.UNRECOGNIZED_FORMAT
        if (candidateArrays.isEmpty()) return@lazy ParserStatus.UNRECOGNIZED_FORMAT
        val totalObjectElements = candidateArrays.sumBy { arr -> (0 until arr.length()).count { arr.opt(it) is JSONObject } }
        if (totalObjectElements == 0) return@lazy ParserStatus.PARSED // a genuinely empty list is a real result
        if (centreArrays.isNotEmpty()) return@lazy ParserStatus.PARSED
        if (parsedSlotsInternal.isNotEmpty()) return@lazy ParserStatus.PARSED
        ParserStatus.UNRECOGNIZED_FORMAT
    }

    private fun bestFieldMatch(obj: JSONObject, orderedHints: List<String>): String? {
        val keys = mutableListOf<String>()
        val it = obj.keys()
        while (it.hasNext()) keys.add(it.next())
        for (hint in orderedHints) {
            for (key in keys) {
                val normalized = key.toLowerCase(Locale.ROOT)
                // Never treat a centre's "nextAvailability" summary as a real slot date/time.
                if (normalized.contains(NEXT_AVAILABILITY_HINT)) continue
                if (normalized.contains(hint)) {
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
     * Generically-extracted REAL appointment slots (test centre / date / time), scanned
     * only from arrays NOT classified as centre lists, never hard-coded to an assumed
     * schema: an element that doesn't confidently yield centre+date+time is skipped,
     * never guessed at, and a centre's "nextAvailability" summary is never used as a
     * slot date (see [bestFieldMatch]).
     */
    private val parsedSlotsInternal: List<DrivingTestSlot> by lazy {
        if (!isAvailabilityFamilyResponse) return@lazy emptyList<DrivingTestSlot>()
        val best = nonCentreArrays.maxBy { arr -> (0 until arr.length()).count { arr.opt(it) is JSONObject } }
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

    /** Public accessor - only non-empty once [parserStatus] confirms this response was understood. */
    val parsedSlots: List<DrivingTestSlot> by lazy {
        if (parserStatus != ParserStatus.PARSED || isCentreListResponse) emptyList() else parsedSlotsInternal
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
        if (isAvailabilityFamilyResponse) {
            sb.append("Endpoint group: ").append(endpointGroup).append('\n')
            when (parserStatus) {
                ParserStatus.PARSED -> {
                    if (isCentreListResponse) {
                        sb.append("CENTRES FOUND: ").append(parsedCentres.size).append('\n')
                    } else {
                        sb.append("REAL SLOTS FOUND: ").append(parsedSlots.size).append('\n')
                        if (detectedFields.isNotEmpty()) {
                            sb.append("Detected fields: ").append(detectedFields.joinToString(", ")).append('\n')
                        }
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
