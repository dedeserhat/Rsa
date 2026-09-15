package ie.rsa.networkinspector

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Exposed to page JavaScript as `window.AndroidLogger`. Only ever receives
 * method/url/timestamp for fetch()/XHR calls, or a bare "block detected"
 * signal - never headers, bodies, cookies or auth tokens.
 */
class WebObserverBridge(
    private val onJsCall: (kind: String, method: String, url: String, timestamp: Long) -> Unit,
    private val onBlockDetected: () -> Unit
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
}
