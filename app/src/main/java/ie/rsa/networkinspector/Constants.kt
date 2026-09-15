package ie.rsa.networkinspector

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
 * Injected once per page load. Records only method/url/timestamp for fetch()
 * and XMLHttpRequest calls the page itself makes - never headers, bodies,
 * cookies or responses - then calls back into the safe [WebObserverBridge].
 */
const val JS_OBSERVER_SCRIPT = """
(function() {
  try {
    if (window.__rsaObserverInstalled) { return; }
    window.__rsaObserverInstalled = true;

    var origFetch = window.fetch;
    if (origFetch) {
      window.fetch = function(input, init) {
        try {
          var method = (init && init.method) ? init.method : 'GET';
          var url = (typeof input === 'string') ? input : ((input && input.url) ? input.url : String(input));
          if (window.AndroidLogger) { window.AndroidLogger.logJsCall('FETCH', method, url, Date.now()); }
        } catch (e) {}
        return origFetch.apply(this, arguments);
      };
    }

    var OrigXHR = window.XMLHttpRequest;
    if (OrigXHR && OrigXHR.prototype && OrigXHR.prototype.open) {
      var origOpen = OrigXHR.prototype.open;
      OrigXHR.prototype.open = function(method, url) {
        try {
          if (window.AndroidLogger) { window.AndroidLogger.logJsCall('XHR', method, String(url), Date.now()); }
        } catch (e) {}
        return origOpen.apply(this, arguments);
      };
    }
  } catch (e) {}
})();
"""

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
