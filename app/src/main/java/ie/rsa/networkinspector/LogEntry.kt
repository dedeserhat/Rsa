package ie.rsa.networkinspector

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Locale

enum class ResourceKind { DOCUMENT, API, FETCH_XHR, IMAGE, SCRIPT, STYLE, FONT, OTHER }

enum class LogSource { WEBVIEW, JS_OBSERVER, SYSTEM }

data class LogEntry(
    val timestamp: Long,
    val method: String?,
    val url: String,
    val isMainFrame: Boolean,
    val isRedirect: Boolean,
    val resourceKind: ResourceKind,
    val source: LogSource
) {
    val host: String = runCatching { Uri.parse(url).host }.getOrNull() ?: ""
    val path: String = runCatching { Uri.parse(url).path }.getOrNull() ?: ""

    /** Query params with sensitive-looking values redacted; safe to display and export. */
    val sanitizedQuery: List<Pair<String, String>> by lazy {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val names = uri?.queryParameterNames ?: emptySet()
        names.map { name ->
            val redacted = SENSITIVE_PARAM_NAMES.any { it.equals(name, ignoreCase = true) }
            val value = if (redacted) "[REDACTED]" else (uri?.getQueryParameter(name) ?: "")
            name to value
        }
    }

    val isFlagged: Boolean by lazy {
        val haystack = (host + path).toLowerCase(Locale.ROOT)
        INTEREST_KEYWORDS.any { haystack.contains(it) }
    }

    val isRsaHost: Boolean by lazy {
        host.contains("roadsafety", ignoreCase = true) || host.contains("rsa.", ignoreCase = true)
    }

    fun formattedTime(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(timestamp)

    fun oneLineSummary(): String {
        val kindTag = "[${resourceKind.name}]"
        val frameTag = if (isMainFrame) " MAIN" else ""
        val redirectTag = if (isRedirect) " REDIRECT" else ""
        val flagTag = if (isFlagged) " ★" else ""
        return "${formattedTime()} ${method ?: "-"} $kindTag$frameTag$redirectTag$flagTag $url"
    }

    fun exportLines(): List<String> {
        val lines = mutableListOf<String>()
        lines += "time=${formattedTime()} source=${source.name} method=${method ?: "-"} kind=${resourceKind.name} mainFrame=$isMainFrame redirect=$isRedirect flagged=$isFlagged"
        lines += "  url=$url"
        lines += "  host=$host path=$path"
        if (sanitizedQuery.isNotEmpty()) {
            lines += "  query=" + sanitizedQuery.joinToString(", ") { (k, v) -> "$k=$v" }
        }
        return lines
    }
}
