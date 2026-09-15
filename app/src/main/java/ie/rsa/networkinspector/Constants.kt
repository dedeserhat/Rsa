package ie.rsa.networkinspector

import android.net.Uri

/** Words that flag a request/page as likely related to the booking flow we're inspecting. */
val INTEREST_KEYWORDS = listOf(
    "availability", "appointment", "booking", "slot", "calendar",
    "test", "centre", "center", "driving", "schedule", "date", "api", "graphql"
)

/** Query-parameter names whose values are never shown, even in-app. */
val SENSITIVE_PARAM_NAMES = setOf(
    "password", "passwd", "pwd", "token", "access_token", "id_token", "refresh_token",
    "auth", "authorization", "session", "sessionid", "session_id", "sid", "cookie",
    "secret", "apikey", "api_key", "key", "code", "otp", "pin"
)

/** Response signatures that mean "stop testing" per the safety requirement. */
val RESTRICTION_HTTP_CODES = setOf(403, 429)
val RESTRICTION_TEXT_MARKERS = listOf(
    "access denied", "too many requests", "rate limit", "temporarily blocked",
    "temporary block", "request blocked", "unusual traffic"
)

/**
 * URL substrings identifying the specific RSA availability/timer endpoints
 * whose response BODIES we're allowed to inspect. Every other request only
 * ever gets method/url/timestamp logged, never its body.
 *
 * Deliberately WITHOUT a leading slash: the RSA SPA's XHR calls use
 * relative URLs (e.g. "api/v1/Availability/slots/..."), and a leading-slash
 * marker never matches those - that was the root cause of response capture
 * silently missing everything while request logging kept working fine.
 */
val AVAILABILITY_URL_MARKERS = listOf(
    "api/v1/Availability/",
    "api/v1/Settings/code/SlotsAvailableTimerInSeconds"
)

const val SLOTS_MARKER = "Availability/slots"
const val CLOSEST_WORKORDER_MARKER = "Availability/ClosestSimpleByWorkOrder"
const val ALL_AVAILABILITY_MARKER = "Availability/All"
const val BY_WORKORDER_TERRITORY_MARKER = "Availability/ByWorkOrderAndTerritory"
const val TIMER_MARKER = "SlotsAvailableTimerInSeconds"

fun isAvailabilityUrl(url: String): Boolean =
    AVAILABILITY_URL_MARKERS.any { url.contains(it, ignoreCase = true) }

/** Rebuilds [url] with any sensitive-looking query values replaced, for safe display/export. */
fun sanitizeUrlForDisplay(url: String): String {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
    val names = uri.queryParameterNames ?: emptySet()
    if (names.isEmpty()) return url
    val builder = uri.buildUpon().clearQuery()
    for (name in names) {
        val redacted = SENSITIVE_PARAM_NAMES.any { it.equals(name, ignoreCase = true) }
        val value = if (redacted) "[REDACTED]" else (uri.getQueryParameter(name) ?: "")
        builder.appendQueryParameter(name, value)
    }
    return builder.build().toString()
}

/** Substrings of JSON field names that hint at a particular kind of slot data. */
val SLOT_FIELD_HINTS: Map<String, List<String>> = linkedMapOf(
    "date" to listOf("date"),
    "start time" to listOf("starttime", "start_time"),
    "end time" to listOf("endtime", "end_time"),
    "time" to listOf("time"),
    "appointment id" to listOf("appointmentid", "appointment_id"),
    "slot id" to listOf("slotid", "slot_id"),
    "test centre" to listOf("centre", "center"),
    "availability status" to listOf("status", "available", "availability")
)

/**
 * Injected as early as WebView allows (see MainActivity's shouldInterceptRequest/
 * onPageStarted/onPageFinished injection points), and re-run idempotently on
 * every one of those hooks. Records only method/url/timestamp for every
 * fetch()/XMLHttpRequest call the page makes - never headers, bodies,
 * cookies or auth tokens - then calls back into the safe [WebObserverBridge].
 *
 * For the specific RSA availability/timer endpoints named in
 * [AVAILABILITY_URL_MARKERS] it additionally inspects the RESPONSE (status,
 * responseURL, responseType, body) via `response.clone()` for fetch, and via
 * `load`/`readystatechange` listeners plus `responseText`/`response` for XHR,
 * without ever touching the response object the page itself consumes.
 *
 * Also reports whether each hook actually installed (fetch may not exist,
 * XHR always should) and, per matching XHR, whether its response was
 * actually captured - so timing problems are visible instead of silent.
 */
fun buildObserverScript(): String {
    val markersJs = AVAILABILITY_URL_MARKERS.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    return """
        (function() {
          try {
            if (window.__rsaObserverInstalled) { return; }
            window.__rsaObserverInstalled = true;
            var installedAt = Date.now();

            var availabilityMarkers = $markersJs;
            function isAvailabilityUrl(u) {
              try {
                if (!u) return false;
                for (var i = 0; i < availabilityMarkers.length; i++) {
                  if (u.indexOf(availabilityMarkers[i]) !== -1) { return true; }
                }
              } catch (e) {}
              return false;
            }
            function safeReportResponse(kind, method, url, status, contentType, bodyText) {
              try {
                var capped = (bodyText && bodyText.length > 200000) ? bodyText.substring(0, 200000) : (bodyText || '');
                if (window.AndroidLogger) {
                  window.AndroidLogger.logApiResponse(kind || '', method || '', url || '', status || 0, contentType || '', capped, Date.now());
                }
              } catch (e) {}
            }

            // --- fetch --------------------------------------------------
            var fetchInstalled = false;
            var origFetch = window.fetch;
            if (origFetch) {
              fetchInstalled = true;
              window.fetch = function(input, init) {
                var method = (init && init.method) ? init.method : 'GET';
                var url = (typeof input === 'string') ? input : ((input && input.url) ? input.url : String(input));
                try {
                  if (window.AndroidLogger) { window.AndroidLogger.logJsCall('FETCH', method, url, Date.now()); }
                } catch (e) {}

                var responsePromise = origFetch.apply(this, arguments);

                if (isAvailabilityUrl(url)) {
                  responsePromise.then(function(response) {
                    try {
                      var cloned = response.clone(); // page still gets the original, untouched response
                      var contentType = '';
                      try { contentType = cloned.headers.get('content-type') || ''; } catch (e) {}
                      cloned.text().then(function(bodyText) {
                        safeReportResponse('FETCH', method, response.url || url, response.status, contentType, bodyText);
                      }).catch(function() {});
                    } catch (e) {}
                  }).catch(function() {});
                }

                return responsePromise; // untouched - caller's promise, never consumed here
              };
            }

            // --- XMLHttpRequest ------------------------------------------
            // Instrument open() (store method/url on the instance) AND send()
            // (attach 'load' + 'readystatechange' listeners) per spec, so the
            // hook exists regardless of which call the SPA's framework code
            // patches around.
            var xhrInstalled = false;
            var OrigXHR = window.XMLHttpRequest;
            if (OrigXHR && OrigXHR.prototype && OrigXHR.prototype.open && OrigXHR.prototype.send) {
              xhrInstalled = true;
              var origOpen = OrigXHR.prototype.open;
              var origSend = OrigXHR.prototype.send;

              OrigXHR.prototype.open = function(method, url) {
                try {
                  this.__rsaMethod = method;
                  this.__rsaUrl = String(url);
                  if (window.AndroidLogger) { window.AndroidLogger.logJsCall('XHR', method, this.__rsaUrl, Date.now()); }
                } catch (e) {}
                return origOpen.apply(this, arguments);
              };

              OrigXHR.prototype.send = function() {
                var xhr = this;
                try {
                  if (!xhr.__rsaHooked && isAvailabilityUrl(xhr.__rsaUrl)) {
                    xhr.__rsaHooked = true;
                    var captured = false;
                    var capture = function() {
                      if (captured || xhr.readyState !== 4) { return; }
                      captured = true;
                      try {
                        if (window.AndroidLogger) { window.AndroidLogger.reportXhrResponseCaptured(xhr.responseURL || xhr.__rsaUrl || ''); }
                      } catch (e) {}
                      var contentType = '';
                      try { contentType = xhr.getResponseHeader('Content-Type') || ''; } catch (e) {}
                      var bodyText = '';
                      try {
                        var rt = xhr.responseType;
                        if (!rt || rt === '' || rt === 'text') {
                          bodyText = xhr.responseText || '';
                        } else if (rt === 'json') {
                          try { bodyText = JSON.stringify(xhr.response); } catch (e2) { bodyText = ''; }
                        } else {
                          try { bodyText = String(xhr.response); } catch (e2) { bodyText = ''; }
                        }
                      } catch (e) {}
                      // Reading responseText/response here does not consume or replace
                      // anything - XHR (unlike a fetch body stream) can be read repeatedly.
                      safeReportResponse('XHR', xhr.__rsaMethod, xhr.responseURL || xhr.__rsaUrl, xhr.status, contentType, bodyText);
                    };
                    xhr.addEventListener('readystatechange', capture);
                    xhr.addEventListener('load', capture);
                  }
                } catch (e) {}
                return origSend.apply(this, arguments); // request itself is never altered or replayed
              };
            }

            try {
              if (window.AndroidLogger) {
                window.AndroidLogger.reportHookStatus(fetchInstalled, xhrInstalled, installedAt);
              }
            } catch (e) {}
          } catch (e) {}
        })();
    """.trimIndent()
}

/**
 * Best-effort, content-safe restriction check: it only ever returns a
 * boolean to Android, never the page text itself.
 */
fun buildBlockDetectorScript(): String {
    val markersJs = RESTRICTION_TEXT_MARKERS.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    return """
        (function() {
          try {
            var markers = $markersJs;
            var text = (document.body && document.body.innerText) ? document.body.innerText.toLowerCase() : '';
            for (var i = 0; i < markers.length; i++) {
              if (text.indexOf(markers[i]) !== -1) {
                if (window.AndroidLogger) { window.AndroidLogger.reportBlockDetected(); }
                break;
              }
            }
          } catch (e) {}
        })();
    """.trimIndent()
}
