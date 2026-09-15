package ie.rsa.networkinspector

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Exposed to page JavaScript as `window.AndroidLogger`. Only ever receives
 * method/url/timestamp for fetch()/XHR calls, a bare "block detected"
 * signal, hook-install/capture diagnostics, or - for the specific RSA
 * availability/timer endpoints only - a response status/content-type/body.
 * Never headers, cookies, login bodies, or auth tokens.
 */
class WebObserverBridge(
    private val onJsCall: (kind: String, method: String, url: String, timestamp: Long) -> Unit,
    private val onBlockDetected: () -> Unit,
    private val onApiResponse: (kind: String, method: String, url: String, status: Int, contentType: String, body: String, timestamp: Long) -> Unit,
    private val onHookStatus: (fetchInstalled: Boolean, xhrInstalled: Boolean, installedAt: Long) -> Unit,
    private val onXhrResponseCaptured: (url: String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun logJsCall(kind: String, method: String, url: String, timestamp: Long) {
        mainHandler.post { onJsCall(kind, method, url, timestamp) }
    }

    @JavascriptInterface
    fun reportBlockDetected() {
        mainHandler.post { onBlockDetected() }
    }

    @JavascriptInterface
    fun logApiResponse(kind: String, method: String, url: String, status: Int, contentType: String, body: String, timestamp: Long) {
        mainHandler.post { onApiResponse(kind, method, url, status, contentType, body, timestamp) }
    }

    @JavascriptInterface
    fun reportHookStatus(fetchInstalled: Boolean, xhrInstalled: Boolean, installedAt: Long) {
        mainHandler.post { onHookStatus(fetchInstalled, xhrInstalled, installedAt) }
    }

    @JavascriptInterface
    fun reportXhrResponseCaptured(url: String) {
        mainHandler.post { onXhrResponseCaptured(url) }
    }
}
