# RSA Network Inspector

A passive Android inspector for observing normal WebView network/navigation
activity while manually browsing `https://www.myroadsafety.ie/` with your own
account. It is **not** an automation or auto-booking tool.

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
