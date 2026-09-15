# RSA Slot Watcher (formerly RSA Network Inspector)

A passive Android inspector for observing normal WebView network/navigation
activity while manually browsing `https://www.myroadsafety.ie/` with your own
account, and for reading (never automating) driving-test availability from
what your own manual browsing already causes the site to return. It is
**not** an automation, polling, or auto-booking tool - see
"What this app deliberately does NOT do" below.

## What it does

- Loads the site in a plain `WebView` with an address bar, Back/Forward/
  Refresh, and log controls (Clear Log / Export Log / Copy Log).
- Logs every observable WebView request/navigation via
  `WebViewClient.shouldInterceptRequest`/`onPageStarted`/`onReceivedHttpError`
  (timestamp, method, URL, host, path, query params, main-frame flag,
  redirect flag, best-effort resource type).
- Injects a small JS shim (`window.fetch` / `XMLHttpRequest.open` wrappers)
  that reports only method + URL + timestamp back to the app, never headers,
  bodies, cookies, or tokens. If injection can't run, WebView-level logging
  still works on its own.
- Flags entries whose URL/host contains booking-flow keywords (availability,
  appointment, booking, slot, calendar, test, centre/center, driving,
  schedule, date, api, graphql) and lets you filter by ALL / API / FETCH-XHR
  / DOCUMENT / RSA ONLY, plus free-text search.
- Shows a full-width **"RSA ACCESS RESTRICTION DETECTED — STOP TESTING"**
  banner on HTTP 403/429 or block-like page text, and never auto-retries.
- Exports a sanitized JSON log via the normal Android share sheet, and
  supports copying the log as plain text. Sensitive-looking query parameter
  values (tokens, session ids, passwords, etc.) are redacted; headers,
  cookies, and request/response bodies are never captured at all.
- Never intercepts TLS: `onReceivedSslError` always cancels the connection,
  no CA is installed, and there is no proxy/MITM layer. Cookies/DOM storage
  persist normally for the life of the app so you can log in manually; there
  is no auto-login and no background/auto-refresh anywhere in the code.

### Response inspector (RSA availability/timer endpoints only)

For requests whose URL contains `api/v1/Availability/` or
`api/v1/Settings/code/SlotsAvailableTimerInSeconds` (covers
`ClosestSimpleByWorkOrder`, `slots`, `All`, `ByWorkOrderAndTerritory`, and the
timer endpoint), the JS shim also inspects the **response** and reports
status, responseURL, content-type, and body back to Android. Every other
request stays metadata-only (method/URL/timestamp). Kotlin re-validates the
URL against the same allow-list before storing anything, so a
compromised/misbehaving page script can't smuggle arbitrary data in under
this channel.

- **fetch**: wraps `window.fetch`, inspects the response via
  `response.clone()` after the original promise resolves, and always returns
  the original, untouched promise to the caller.
- **XHR**: instruments both `.open()` (stores method/url on the instance) and
  `.send()` (attaches `load` **and** `readystatechange` listeners, per spec,
  keyed off the URL captured at `open()` time); on DONE it reads
  `status`/`responseURL`/`responseType`, using `responseText` for `""`/`text`,
  `JSON.stringify(response)` for `"json"`, and a best-effort `String(response)`
  for anything else - never replacing, consuming, or replaying the request or
  response.
- **Root cause of the original miss**: the URL marker required a leading `/`,
  but the RSA SPA's XHR calls use relative URLs (no leading slash), so the
  match silently always failed even though the separate, unconditional
  request-logging call still fired - which is exactly why "GET (XHR)" entries
  showed up while `availabilityResponses` stayed empty. Fixed by dropping the
  leading slash from the markers.
- **Diagnostics**: a persistent header line shows
  `XHR HOOK INSTALLED: YES/NO`, `FETCH HOOK INSTALLED: YES/NO`, and
  `HOOK INSTALLED AT: [time]`, refreshed on every injection. A matching XHR
  reaching DONE adds an `XHR RESPONSE CAPTURED` line to the log immediately.
  If the native layer sees a request matching `Availability/slots` but no
  matching JS-reported response shows up within 6 seconds, it adds
  `WARNING: SLOT REQUEST OBSERVED BUT RESPONSE HOOK MISSED IT` so a future
  timing regression is visible instead of silent.
- **Injection timing**: the observer script is posted from
  `shouldInterceptRequest` the moment the main-frame request is seen (earlier
  than `onPageStarted`), and again from `onPageStarted`/`onPageFinished` as a
  fallback; `window.__rsaObserverInstalled` makes re-injection a no-op. True
  guaranteed-before-any-page-script injection needs
  `WebViewCompat.addDocumentStartJavaScript` (`androidx.webkit`), which isn't
  fetchable in this sandbox (see build notes below) - the request/response
  correlation warning above exists specifically to surface it if that gap
  ever matters in practice.
- **AVAILABILITY tab**: shows only these captured responses as cards
  (METHOD / STATUS / URL / TIME / pretty-printed JSON response).
- **Timer detection**: a `SlotsAvailableTimerInSeconds` response shows
  "RSA SLOT TIMER VALUE: …" in a banner - the raw server value, display-only,
  never used to schedule anything.
- **Slot analysis**: an `Availability/slots` JSON response is scanned
  (read-only) for likely date/time/appointment-id/slot-id/test-centre/
  status fields and shows "SLOTS FOUND: N".
- **AUTH TEST INSTRUCTIONS** button shows static instructions (capture while
  logged in → export → log out normally) - it does not touch auth itself.
- Export JSON gained an `availabilityResponses` array (timestamp, method,
  sanitizedUrl, status, contentType, responseBody); query values keep being
  redacted the same way as the rest of the log.
- On HTTP 403/429 from any request, including these endpoints, the app shows
  the stop-testing banner and disables the Refresh button until you dismiss
  it - no retry, no polling, no proxy rotation, no CAPTCHA/Incapsula/Queue-it
  handling. **There is still no automatic polling anywhere in this app.**

### Slot Watcher additions (Work-Order, redaction, parsing, notifications)

- **Capture allow-list** now also includes `api/v1/Work-Order/`, alongside
  Availability and the timer endpoint. `Contact/current` is explicitly
  **never** captured (checked before anything else), even if it happened to
  match the allow-list.
- **Deep redaction** (`redactSensitiveJson` in `Constants.kt`): before a
  captured body is ever stored, every JSON key whose name contains
  password/token/authorization/cookie/session/email/phone/address/PPSN/
  driver-or-licence-number/MyGovID has its value replaced with
  `[REDACTED]`, and any nested `contact` object is dropped entirely (key
  matching is case/punctuation-insensitive: `Access_Token`, `accessToken`,
  `ACCESSTOKEN` all match). Falls back to the untouched input if the body
  isn't valid JSON - it never invents structure to redact around.
- **AVAILABILITY tab**: unchanged in spirit, now also shows Work-Order
  responses; **"Copy Sanitized Availability JSON"** copies every captured
  (already-redacted) response body to the clipboard.
- **CENTRES tab**: for responses shaped like the real
  `Availability/ByWorkOrderAndTerritory` schema (`id`, `name`, plus a
  `nextAvailability` field and at least one of `county`/`territoryId`/
  `latitude`/`longitude`/`providesRequestedServices`/`isClosest`) - matched
  by URL (`ByWorkOrderAndTerritory`, `ClosestSimpleByWorkOrder`) **or** by
  that object shape, so a similarly-shaped "Other" endpoint is still caught.
  Shows centre name + `nextAvailability`, treating `"0001-01-01T00:00:00Z"`/
  null/empty as "NO DATE AVAILABLE" and highlighting a genuine later date.
  Notifies "RSA availability detected — {centre} — {date/time}" only for a
  genuine date, deduplicated by centre id + nextAvailability (a later/earlier
  date re-notifies).
- **SLOTS tab ("REAL SLOT RESPONSE" / "AVAILABLE DRIVING TESTS")**: a
  schema-agnostic extractor (`AvailabilityResponseEntry.parsedSlots`)
  recursively finds JSON arrays anywhere in a response, **explicitly
  excluding any array classified as a centre list** (see above) and never
  matching a `nextAvailability`-named field as a slot date, then for each
  remaining object tries ordered field-name hints
  (`CENTRE_FIELD_HINTS`/`DATE_FIELD_HINTS`/`TIME_FIELD_HINTS`/
  `DATETIME_FIELD_HINTS` in `Constants.kt`) to pull a centre/date/time -
  skipping (never guessing) any element it can't confidently read, and never
  inferring a slot count from raw array length. **We still don't have a real
  captured response that returns individual appointment dates/times** - only
  `ByWorkOrderAndTerritory` (centres) has been confirmed so far - so this
  stays empty with an explicit "no confirmed appointment-slot response
  observed yet" message until the real endpoint is identified and its exact
  fields are captured.
- **Fail-safe (`ParserStatus`)**: `PARSED` (found at least one array and
  classified it as centres or slots - even a genuinely empty array is a real
  "0" result) vs. `UNRECOGNIZED_FORMAT` (body didn't parse as JSON, had no
  array-shaped data anywhere, or had elements neither shape recognized) -
  the UI and DEBUG tab show
  "RSA response format changed — parser update required" for the latter
  instead of ever silently reporting zero.
- **DEBUG tab endpoint breakdown**: every captured Availability response is
  grouped and counted by `endpointGroup` (`ByWorkOrderAndTerritory` /
  `ClosestSimpleByWorkOrder` / `slots` / `All` / `Other`), shown both as a
  per-card label in the AVAILABILITY tab and as counts in DEBUG - to help
  identify which endpoint eventually turns out to carry real appointment
  data.
- **Notifications** (`NotificationHelper.kt`, HIGH-importance channel for
  slots, DEFAULT for session): fire only from a response your own manual
  WebView use already produced - never from a scheduler. A slot notifies
  once per fingerprint (`centre|date|time`, persisted in
  `SlotFingerprintStore` via SharedPreferences so it survives restarts) and
  re-notifies if a genuinely new/different slot appears. A 401/403 from
  Work-Order/Availability flips session state to "Login required" and
  notifies once per transition (not every request). Tapping either
  notification just brings the app to the foreground - it never
  auto-navigates into a specific booking step.
- **Dashboard (status banner)**: Session (🟢 Logged in / 🟡 Login required),
  fixed category label (Car & Light Van (B)), last checked time, and current
  slot count - all derived from the last response your own browsing caused.
- **DEBUG tab**: last request (sanitized URL), last HTTP status, last
  successful check time, slot count, parser status, plus
  **"Export Sanitized Debug Log"** - never shows cookies/tokens/headers.

### What this app deliberately does NOT do

The original spec for this iteration asked for a Settings screen with a
monitoring interval, a `WorkManager`-based background poller, a foreground
service ("RSA Slot Watcher is active"), and a rate-limited "Check Now"
button - i.e., the app checking RSA's availability API on its own schedule
even when you're not looking at the phone. That part was intentionally not
built. Every safety rail in that spec (reading the server's own
`SlotsAvailableTimerInSeconds` value, a 5-minute floor, jitter, exponential
backoff, stopping on 403/429) is a genuinely careful design for *how* to
poll politely - but the thing itself is still an automated, unattended
client checking a scarce public-service booking system faster than a human
would, which gives whoever runs it an edge over everyone else checking by
hand. That's true regardless of how nicely it treats RSA's rate limits, and
it's a different concern from CAPTCHA/Incapsula/Queue-it bypass, IP
rotation, or auto-booking (all of which this app also never does). Every
feature that *is* built above is purely reactive to responses your own
manual WebView navigation already caused - the app never decides on its own
to make, or cause the page to make, a network request.

## Project layout

Standard Android Gradle project (Kotlin, no third-party dependencies -
platform SDK + Kotlin stdlib only):

```
app/src/main/AndroidManifest.xml
app/src/main/java/ie/rsa/networkinspector/*.kt
app/src/main/res/...
build.gradle.kts, settings.gradle.kts, app/build.gradle.kts
```

`minSdk 29` (Android 10), `targetSdk`/`compileSdk 34`.

## Building normally (Android Studio / a machine with normal internet access)

```
./gradlew assembleDebug
```

This produces `app/build/outputs/apk/debug/app-debug.apk` the standard way,
using the Android Gradle Plugin and the real Android SDK/AAPT2/D8 toolchain.

## How this particular debug APK was built

The sandbox this project was generated in has no route to `dl.google.com`/
`maven.google.com` (where the Android SDK and AGP are normally fetched from),
so the Gradle build above could not run here. Instead the APK was produced
with a manual toolchain assembled from packages already reachable in that
sandbox:

- `aapt2`/`aapt`/`zipalign`/`apksigner` from Ubuntu's `android-sdk-build-tools`
  package family, and the real Android `dx` dex compiler from the
  `dalvik-exchange` package.
- A genuine Android API 34 `android.jar` (classes + resources.arsc) fetched
  from the public `Sable/android-platforms` mirror on GitHub, used only as
  the compile/resource-link classpath (never shipped in the APK).
- `kotlinc` 1.3.31 (Ubuntu's `kotlin` package) compiling straight to
  `.class` files, then `dx` converting those plus `kotlin-stdlib.jar` to
  `classes.dex`.
- The result was zipaligned and signed with a locally generated debug
  keystore (`apksigner`), matching what `assembleDebug` would hand you.

None of that is part of the checked-in project - it's just how this one APK
got built in a network-restricted sandbox. On a normal machine, use
`./gradlew assembleDebug` or Android Studio as usual.
