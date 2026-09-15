package ie.rsa.networkinspector

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "RsaNetworkInspector"
private const val DEFAULT_URL = "https://www.myroadsafety.ie/"

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var logListView: ListView
    private lateinit var searchBox: EditText
    private lateinit var logPanelBody: View
    private lateinit var logToggleHeader: TextView
    private lateinit var restrictionBanner: TextView
    private lateinit var timerBanner: TextView
    private lateinit var diagnosticBanner: TextView
    private lateinit var refreshButton: Button
    private lateinit var filterRow: View
    private lateinit var filterAll: Button
    private lateinit var filterApi: Button
    private lateinit var filterFetch: Button
    private lateinit var filterDocument: Button
    private lateinit var filterRsa: Button
    private lateinit var tabLogButton: Button
    private lateinit var tabAvailabilityButton: Button
    private lateinit var authTestButton: Button

    private val adapter by lazy { LogAdapter(this) }
    private val availabilityAdapter by lazy { AvailabilityAdapter(this) }
    private val allEntries = mutableListOf<LogEntry>()
    private val availabilityEntries = mutableListOf<AvailabilityResponseEntry>()

    /** Availability-endpoint URLs seen by the native shouldInterceptRequest layer that
     *  haven't been matched by a JS-reported response yet - used only for the
     *  "response hook missed it" diagnostic, never to act on the network itself. */
    private val pendingAvailabilityRequests = mutableListOf<Pair<String, Long>>()
    private val diagnosticHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private enum class FilterMode { ALL, API, FETCH_XHR, DOCUMENT, RSA_ONLY }
    private enum class ViewMode { LOG, AVAILABILITY }
    private var currentFilter = FilterMode.ALL
    private var currentMode = ViewMode.LOG
    private var searchQuery: String = ""
    private var logPanelExpanded = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        configureWebView()
        wireControls()

        webView.loadUrl(DEFAULT_URL)
        addressBar.setText(DEFAULT_URL)
    }

    private fun bindViews() {
        webView = findViewById(R.id.webView)
        addressBar = findViewById(R.id.addressBar)
        logListView = findViewById(R.id.logListView)
        searchBox = findViewById(R.id.searchBox)
        logPanelBody = findViewById(R.id.logPanelBody)
        logToggleHeader = findViewById(R.id.logToggleHeader)
        restrictionBanner = findViewById(R.id.restrictionBanner)
        timerBanner = findViewById(R.id.timerBanner)
        diagnosticBanner = findViewById(R.id.diagnosticBanner)
        refreshButton = findViewById(R.id.refreshButton)
        filterRow = findViewById(R.id.filterRow)
        filterAll = findViewById(R.id.filterAll)
        filterApi = findViewById(R.id.filterApi)
        filterFetch = findViewById(R.id.filterFetch)
        filterDocument = findViewById(R.id.filterDocument)
        filterRsa = findViewById(R.id.filterRsa)
        tabLogButton = findViewById(R.id.tabLogButton)
        tabAvailabilityButton = findViewById(R.id.tabAvailabilityButton)
        authTestButton = findViewById(R.id.authTestButton)

        logListView.adapter = adapter
    }

    private fun configureWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // Leave the user agent at its WebView default so the site sees normal browser behaviour.

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val bridge = WebObserverBridge(
            onJsCall = { kind, method, url, timestamp -> onJsObservedCall(kind, method, url, timestamp) },
            onBlockDetected = { showRestrictionWarning("Page content matches a rate-limit/blocked pattern") },
            onApiResponse = { kind, method, url, status, contentType, body, timestamp ->
                onApiResponseObserved(kind, method, url, status, contentType, body, timestamp)
            },
            onHookStatus = { fetchInstalled, xhrInstalled, installedAt -> onHookStatusReported(fetchInstalled, xhrInstalled, installedAt) },
            onXhrResponseCaptured = { url -> onXhrResponseCapturedReported(url) }
        )
        webView.addJavascriptInterface(bridge, "AndroidLogger")

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Never intercept navigation ourselves - only observe it.
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                recordWebViewRequest(request)
                if (request.isForMainFrame) {
                    // Earliest point WebView gives us a hook on this navigation - post the
                    // observer script for the instant the UI thread is free, well before
                    // onPageStarted/onPageFinished. May run on a non-UI thread here.
                    view.post { view.evaluateJavascript(buildObserverScript(), null) }
                }
                return null // Always let WebView perform the real request unmodified.
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                addressBar.setText(url)
                view.evaluateJavascript(buildObserverScript(), null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                addressBar.setText(url)
                view.evaluateJavascript(buildObserverScript(), null)
                view.evaluateJavascript(buildBlockDetectorScript(), null)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                val code = errorResponse.statusCode
                if (code in RESTRICTION_HTTP_CODES) {
                    showRestrictionWarning("HTTP $code from ${Uri.parse(request.url.toString()).host}")
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "Main frame load error: ${error.description}")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // Never bypass certificate validation: always cancel.
                Log.w(TAG, "TLS error encountered, cancelling connection: ${error.primaryError}")
                appendEntry(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        method = null,
                        url = error.url ?: "unknown",
                        isMainFrame = false,
                        isRedirect = false,
                        resourceKind = ResourceKind.OTHER,
                        source = LogSource.SYSTEM
                    )
                )
                handler.cancel()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                title = if (newProgress in 1..99) "Loading… $newProgress%" else getString(R.string.app_name)
            }
        }
    }

    private fun wireControls() {
        addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigateToAddressBarUrl()
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.goButton).setOnClickListener { navigateToAddressBarUrl() }
        findViewById<Button>(R.id.backButton).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        findViewById<Button>(R.id.forwardButton).setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        refreshButton.setOnClickListener {
            // Manual refresh only - the app never reloads pages on its own.
            webView.reload()
        }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener { clearLog() }
        findViewById<Button>(R.id.exportLogButton).setOnClickListener { exportLog() }
        findViewById<Button>(R.id.copyLogButton).setOnClickListener { copyLogToClipboard() }

        logToggleHeader.setOnClickListener {
            logPanelExpanded = !logPanelExpanded
            logPanelBody.visibility = if (logPanelExpanded) View.VISIBLE else View.GONE
            logToggleHeader.text = getString(
                if (logPanelExpanded) R.string.log_panel_title_expanded else R.string.log_panel_title_collapsed
            )
        }

        restrictionBanner.setOnClickListener {
            restrictionBanner.visibility = View.GONE
            // Dismissing the banner is the user's explicit re-enable of manual refresh.
            refreshButton.isEnabled = true
        }
        timerBanner.setOnClickListener { timerBanner.visibility = View.GONE }

        filterAll.setOnClickListener { setFilter(FilterMode.ALL) }
        filterApi.setOnClickListener { setFilter(FilterMode.API) }
        filterFetch.setOnClickListener { setFilter(FilterMode.FETCH_XHR) }
        filterDocument.setOnClickListener { setFilter(FilterMode.DOCUMENT) }
        filterRsa.setOnClickListener { setFilter(FilterMode.RSA_ONLY) }

        tabLogButton.setOnClickListener { switchMode(ViewMode.LOG) }
        tabAvailabilityButton.setOnClickListener { switchMode(ViewMode.AVAILABILITY) }
        authTestButton.setOnClickListener { showAuthTestInstructions() }

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                refreshVisibleList()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        logListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val url = when (currentMode) {
                ViewMode.LOG -> adapter.getItem(position).url
                ViewMode.AVAILABILITY -> availabilityAdapter.getItem(position).sanitizedUrl
            }
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    private fun switchMode(mode: ViewMode) {
        currentMode = mode
        when (mode) {
            ViewMode.LOG -> {
                filterRow.visibility = View.VISIBLE
                searchBox.visibility = View.VISIBLE
                logListView.adapter = adapter
                refreshVisibleList()
            }
            ViewMode.AVAILABILITY -> {
                filterRow.visibility = View.GONE
                searchBox.visibility = View.GONE
                logListView.adapter = availabilityAdapter
                refreshAvailabilityList()
            }
        }
    }

    private fun showAuthTestInstructions() {
        AlertDialog.Builder(this)
            .setTitle(R.string.auth_test_title)
            .setMessage(R.string.auth_test_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun navigateToAddressBarUrl() {
        var text = addressBar.text.toString().trim()
        if (text.isEmpty()) return
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            text = "https://$text"
        }
        webView.loadUrl(text)
    }

    private fun recordWebViewRequest(request: WebResourceRequest) {
        val url = request.url.toString()
        val kind = guessResourceKind(request)
        val isRedirect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) request.isRedirect else false
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            method = request.method,
            url = url,
            isMainFrame = request.isForMainFrame,
            isRedirect = isRedirect,
            resourceKind = kind,
            source = LogSource.WEBVIEW
        )
        runOnUiThread {
            appendEntry(entry)
            if (isAvailabilityUrl(url)) {
                trackPendingAvailabilityRequest(url)
            }
        }
    }

    /** Records that the native layer saw a matching request, so we can flag it later if
     *  no JS-reported response ever shows up for it (see [checkForMissedResponse]). */
    private fun trackPendingAvailabilityRequest(url: String) {
        pendingAvailabilityRequests.add(url to System.currentTimeMillis())
        diagnosticHandler.postDelayed({ checkForMissedResponse(url) }, 6000L)
    }

    private fun clearPendingAvailabilityRequest(url: String) {
        pendingAvailabilityRequests.removeAll { (pendingUrl, _) -> pendingUrl == url || pendingUrl.contains(url) || url.contains(pendingUrl) }
    }

    private fun checkForMissedResponse(url: String) {
        val stillPending = pendingAvailabilityRequests.any { (pendingUrl, _) -> pendingUrl == url }
        pendingAvailabilityRequests.removeAll { (pendingUrl, _) -> pendingUrl == url }
        if (stillPending && url.contains(SLOTS_MARKER, ignoreCase = true)) {
            appendEntry(
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    method = "WARNING: SLOT REQUEST OBSERVED BUT RESPONSE HOOK MISSED IT",
                    url = url,
                    isMainFrame = false,
                    isRedirect = false,
                    resourceKind = ResourceKind.OTHER,
                    source = LogSource.SYSTEM
                )
            )
        }
    }

    private fun guessResourceKind(request: WebResourceRequest): ResourceKind {
        if (request.isForMainFrame) return ResourceKind.DOCUMENT
        val path = request.url.path?.toLowerCase(Locale.ROOT) ?: ""
        val accept = request.requestHeaders["Accept"]?.toLowerCase(Locale.ROOT) ?: ""
        return when {
            path.contains("graphql") || path.contains("/api/") -> ResourceKind.API
            accept.contains("application/json") -> ResourceKind.API
            path.endsWith(".js") -> ResourceKind.SCRIPT
            path.endsWith(".css") -> ResourceKind.STYLE
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".webp") -> ResourceKind.IMAGE
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") -> ResourceKind.FONT
            accept == "*/*" -> ResourceKind.FETCH_XHR
            else -> ResourceKind.OTHER
        }
    }

    private fun onJsObservedCall(kind: String, method: String, url: String, timestamp: Long) {
        val entry = LogEntry(
            timestamp = timestamp,
            method = "$method ($kind)",
            url = url,
            isMainFrame = false,
            isRedirect = false,
            resourceKind = ResourceKind.FETCH_XHR,
            source = LogSource.JS_OBSERVER
        )
        appendEntry(entry)
    }

    private fun appendEntry(entry: LogEntry) {
        allEntries.add(entry)
        if (allEntries.size > 5000) {
            allEntries.removeAt(0)
        }
        refreshVisibleList()
    }

    /**
     * Response for one of the RSA availability/timer endpoints, reported by the
     * JS observer via response.clone() (fetch) or responseText (XHR). Re-checks
     * the URL against the same allow-list the JS uses before trusting it at all.
     */
    private fun onApiResponseObserved(
        kind: String,
        method: String,
        url: String,
        status: Int,
        contentType: String,
        body: String,
        timestamp: Long
    ) {
        if (!isAvailabilityUrl(url)) return
        clearPendingAvailabilityRequest(url)
        val cappedBody = if (body.length > 50_000) body.substring(0, 50_000) else body
        val entry = AvailabilityResponseEntry(
            timestamp = timestamp,
            method = "$method ($kind)",
            url = url,
            status = status,
            contentType = contentType,
            rawBody = cappedBody,
            source = LogSource.JS_OBSERVER
        )
        availabilityEntries.add(entry)
        if (availabilityEntries.size > 500) {
            availabilityEntries.removeAt(0)
        }

        if (entry.isTimerResponse) {
            entry.timerValueSeconds?.let {
                timerBanner.text = getString(R.string.timer_value_format, it)
                timerBanner.visibility = View.VISIBLE
            }
        }

        if (status in RESTRICTION_HTTP_CODES) {
            showRestrictionWarning("HTTP $status from availability API")
        }

        if (currentMode == ViewMode.AVAILABILITY) {
            refreshAvailabilityList()
        }
    }

    private fun refreshAvailabilityList() {
        availabilityAdapter.setItems(availabilityEntries.asReversed())
    }

    /** Diagnostic: whether the fetch/XHR hooks actually installed for this page, and when. */
    private fun onHookStatusReported(fetchInstalled: Boolean, xhrInstalled: Boolean, installedAt: Long) {
        diagnosticBanner.text =
            "XHR HOOK INSTALLED: ${if (xhrInstalled) "YES" else "NO"}   " +
                "FETCH HOOK INSTALLED: ${if (fetchInstalled) "YES" else "NO"}   " +
                "HOOK INSTALLED AT: ${timeFormat.format(installedAt)}"
    }

    /** Diagnostic: a matching XHR reached DONE and its response was actually read. */
    private fun onXhrResponseCapturedReported(url: String) {
        clearPendingAvailabilityRequest(url)
        appendEntry(
            LogEntry(
                timestamp = System.currentTimeMillis(),
                method = "XHR RESPONSE CAPTURED",
                url = url,
                isMainFrame = false,
                isRedirect = false,
                resourceKind = ResourceKind.OTHER,
                source = LogSource.SYSTEM
            )
        )
    }

    private fun setFilter(mode: FilterMode) {
        currentFilter = mode
        refreshVisibleList()
    }

    private fun refreshVisibleList() {
        val query = searchQuery.toLowerCase(Locale.ROOT)
        val filtered = allEntries.filter { entry ->
            val matchesFilter = when (currentFilter) {
                FilterMode.ALL -> true
                FilterMode.API -> entry.resourceKind == ResourceKind.API
                FilterMode.FETCH_XHR -> entry.resourceKind == ResourceKind.FETCH_XHR || entry.source == LogSource.JS_OBSERVER
                FilterMode.DOCUMENT -> entry.resourceKind == ResourceKind.DOCUMENT
                FilterMode.RSA_ONLY -> entry.isRsaHost
            }
            val matchesSearch = query.isEmpty() ||
                entry.url.toLowerCase(Locale.ROOT).contains(query) ||
                entry.host.toLowerCase(Locale.ROOT).contains(query)
            matchesFilter && matchesSearch
        }
        adapter.setItems(filtered.asReversed())
    }

    private fun clearLog() {
        allEntries.clear()
        availabilityEntries.clear()
        timerBanner.visibility = View.GONE
        refreshVisibleList()
        refreshAvailabilityList()
    }

    private fun exportLog() {
        if (allEntries.isEmpty() && availabilityEntries.isEmpty()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val file = LogExporter.writeJson(this, allEntries, availabilityEntries)
        startActivity(LogExporter.shareIntentFor(this, file))
    }

    private fun copyLogToClipboard() {
        if (allEntries.isEmpty()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("RSA Network Inspector log", LogExporter.plainTextFor(allEntries))
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun showRestrictionWarning(detail: String) {
        Log.w(TAG, "Restriction detected: $detail")
        restrictionBanner.text = getString(R.string.restriction_warning)
        restrictionBanner.visibility = View.VISIBLE
        // Stop the one piece of inspector-generated network activity we control.
        // Manual navigation via the address bar / links is left entirely up to you.
        refreshButton.isEnabled = false
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        // Keep cookies/session data persisted while the app is in the foreground or backgrounded.
        CookieManager.getInstance().flush()
    }
}
