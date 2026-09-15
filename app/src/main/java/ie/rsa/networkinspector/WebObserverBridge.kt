package ie.rsa.networkinspector

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Exposed to page JavaScript as `window.AndroidLogger`. Only ever receives
 * method/url/timestamp for fetch()/XHR calls, a bare "block detected"
 * signal, or - for the specific RSA availability/timer endpoints only - a
 * response status/content-type/body. Never headers, cookies, login bodies,
 * or auth tokens.
 */
class WebObserverBridge(
    private val onJsCall: (kind: String, method: String, url: String, timestamp: Long) -> Unit,
    private val onBlockDetected: () -> Unit,
    private val onApiResponse: (method: String, url: String, status: Int, contentType: String, body: String, timestamp: Long) -> Unit
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
    fun logApiResponse(method: String, url: String, status: Int, contentType: String, body: String, timestamp: Long) {
        mainHandler.post { onApiResponse(method, url, status, contentType, body, timestamp) }
    }
}
