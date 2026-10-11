package io.github.anvveber.hyperion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONException
import org.json.JSONObject

/**
 * The app itself: the hyperion webapp in a WebView, with the Android pieces a
 * browser tab lacks (file picking, downloads, back button, system bar colours,
 * launcher shortcuts, a proper error screen).
 */
class MainActivity : Activity() {
    private lateinit var server: Uri
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorMessage: TextView

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var currentMainUrl: String? = null
    private var failedUrl: String? = null
    private var bridgeInjectedAtStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val configured = ServerConfig.get(this)
        if (configured == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        server = configured

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        webView = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error)
        errorMessage = findViewById(R.id.error_message)
        root.padForSystemBars()

        findViewById<Button>(R.id.retry).setOnClickListener { retry() }
        findViewById<Button>(R.id.change_server).setOnClickListener { openSetup() }

        configureWebView()

        val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
        if (!restored) webView.loadUrl(startUrl(intent).toString())
        if (intent?.action == ACTION_CHANGE_SERVER) openSetup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            ACTION_CHANGE_SERVER -> openSetup()
            ACTION_OPEN_PATH -> webView.loadUrl(startUrl(intent).toString())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        // Persist the login cookie now rather than whenever WebView gets round to it.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        // onRenderProcessGone may already have removed and destroyed it.
        if (::webView.isInitialized && webView.parent != null) {
            root.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (errorView.visibility == View.VISIBLE && webView.canGoBack()) {
            hideError()
            webView.goBack()
        } else if (errorView.visibility != View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_FILES) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val callback = fileCallback ?: return
        fileCallback = null
        val picked: Array<Uri>? = if (resultCode != RESULT_OK || data == null) {
            null
        } else {
            val clip = data.clipData
            if (clip != null && clip.itemCount > 0) {
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            } else {
                data.data?.let { arrayOf(it) }
            }
        }
        callback.onReceiveValue(picked)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.setBackgroundColor(getColor(R.color.background))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            // Safe Browsing would send visited URLs to Google. The only site here is your own server.
            safeBrowsingEnabled = false
            // Lets the webapp recognise the app (it hides its "install app" prompt).
            userAgentString = "$userAgentString HyperionAndroid/${BuildConfig.VERSION_NAME}"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // The webapp has its own dark theme; WebView's automatic darkening would fight it.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }
        CookieManager.getInstance().setAcceptCookie(true)

        installBridge()
        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    /** Wires up bridge.js, limited to the server's own origin. */
    private fun installBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val origins = setOf(ServerConfig.origin(server))
        WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, origins) { _, message, _, isMainFrame, _ ->
            if (isMainFrame) message.data?.let { onBridgeMessage(it) }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, bridgeScript, origins)
            bridgeInjectedAtStart = true
        }
    }

    private val bridgeScript: String by lazy {
        resources.openRawResource(R.raw.bridge).bufferedReader().use { it.readText() }
    }

    private fun onBridgeMessage(raw: String) {
        val message = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return
        }
        when (message.optString("type")) {
            "theme" -> applyPageTheme(message.optString("color"), message.optBoolean("dark", true))
            "download" -> {
                val name = message.optString("name", "download")
                val mime = message.optString("mime", "application/octet-stream")
                val data = message.optString("data")
                Thread {
                    val saved = Downloads.saveBase64(applicationContext, name, mime, data)
                    runOnUiThread {
                        val text = if (saved != null) {
                            getString(R.string.download_saved, saved)
                        } else {
                            getString(R.string.download_failed)
                        }
                        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            "download-failed" -> Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Paints the area behind the system bars like the page, and picks readable bar icons. */
    private fun applyPageTheme(color: String, dark: Boolean) {
        val parsed = try {
            Color.parseColor(color)
        } catch (e: IllegalArgumentException) {
            if (dark) Color.BLACK else Color.parseColor("#F4F4F4")
        }
        val light = ColorUtils.calculateLuminance(parsed) > 0.5
        root.setBackgroundColor(parsed)
        progress.progressTintList = ColorStateList.valueOf(if (light) Color.BLACK else Color.WHITE)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun onDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            // blob: and data: links are handled in the page by bridge.js. Reaching
            // here means the bridge is unavailable on this WebView version.
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val name = Downloads.enqueue(this, url, userAgent, contentDisposition, mimeType)
            Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun startUrl(intent: Intent?): Uri =
        if (intent?.action == ACTION_OPEN_PATH) {
            ServerConfig.resolve(server, intent.getStringExtra(EXTRA_PATH))
        } else {
            server
        }

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun retry() {
        val target = failedUrl ?: server.toString()
        hideError()
        webView.loadUrl(target)
    }

    private fun showError(message: String) {
        errorMessage.text = message
        errorView.visibility = View.VISIBLE
        progress.visibility = View.GONE
    }

    private fun hideError() {
        failedUrl = null
        errorView.visibility = View.GONE
    }

    /** Hands a link to whichever app handles it: browser, dialer, mail, maps, messengers. */
    private fun openExternally(uri: Uri) {
        val intent = when (uri.scheme?.lowercase()) {
            "http", "https", "mailto", "tel", "sms", "smsto", "geo", "sgnl", "tg", "whatsapp", "xmpp", "matrix" ->
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            "intent" -> parseIntentUri(uri) ?: return
            // file:, content:, javascript: and anything unknown never leave the WebView.
            else -> return
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")?.let { Uri.parse(it) }
            if (fallback != null && (fallback.scheme == "https" || fallback.scheme == "http")) {
                openExternally(fallback)
            } else {
                Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** intent: URIs, stripped down so a page cannot target a specific (possibly private) component. */
    private fun parseIntentUri(uri: Uri): Intent? = try {
        Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            component = null
            selector = null
        }
    } catch (e: java.net.URISyntaxException) {
        null
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            // Every http(s) navigation in the main window stays in the app, including
            // other hosts: login pages in front of the server (Pangolin, OIDC
            // providers) redirect away and back, and must keep their cookies here.
            // Links meant for the outside world use target="_blank" and are
            // handled in onCreateWindow.
            if (scheme == "http" || scheme == "https") return false
            if (request.isForMainFrame) openExternally(request.url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            currentMainUrl = url
            failedUrl = null
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String) {
            progress.visibility = View.GONE
            if (failedUrl == null) errorView.visibility = View.GONE
            if (!bridgeInjectedAtStart && ServerConfig.isSameOrigin(Uri.parse(url), server)) {
                // Older WebView without document-start scripts: inject late instead.
                view.evaluateJavascript(bridgeScript, null)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            failedUrl = request.url.toString()
            showError(getString(R.string.error_network, request.url.host ?: request.url.toString()))
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Never proceed past a bad certificate.
            handler.cancel()
            if (error.url == currentMainUrl) {
                failedUrl = error.url
                showError(getString(R.string.error_ssl, Uri.parse(error.url).host ?: error.url))
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer crashed or was killed for memory. Start over
            // with a fresh WebView instead of taking the whole app down.
            root.removeView(view)
            view.destroy()
            recreate()
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            if (!isUserGesture) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            // A throwaway WebView that only exists to learn the URL of a
            // target="_blank" link or window.open() call.
            val popup = WebView(this@MainActivity)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(popupView: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (ServerConfig.isSameOrigin(url, server)) {
                        webView.loadUrl(url.toString())
                    } else {
                        openExternally(url)
                    }
                    popupView.post { popupView.destroy() }
                    return true
                }
            }
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
            val types = pickerMimeTypes(params.acceptTypes)
            when (types.size) {
                0 -> intent.type = "*/*"
                1 -> intent.type = types[0]
                else -> {
                    intent.type = "*/*"
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                }
            }
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_FILES)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                Toast.makeText(this@MainActivity, R.string.no_file_picker, Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    companion object {
        const val ACTION_OPEN_PATH = "io.github.anvveber.hyperion.OPEN_PATH"
        const val ACTION_CHANGE_SERVER = "io.github.anvveber.hyperion.CHANGE_SERVER"
        const val EXTRA_PATH = "path"
        private const val BRIDGE_NAME = "HyperionAndroid"
        private const val REQUEST_FILES = 1

        /**
         * WebView's own FileChooserParams.createIntent() only uses the first
         * accept type, which would hide PNGs behind accept="image/jpeg,image/png".
         * This keeps all of them. When an entry maps to something other than an
         * image or audio type (".json", ".vcf"), document providers report those
         * MIME types too inconsistently to filter on, so the picker shows everything
         * and the server does the validating.
         */
        fun pickerMimeTypes(accept: Array<String>?): List<String> {
            val types = LinkedHashSet<String>()
            accept.orEmpty()
                .flatMap { it.split(',') }
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .forEach { entry ->
                    val mime = if (entry.startsWith(".")) {
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.removePrefix(".")) ?: return emptyList()
                    } else {
                        entry
                    }
                    if (!mime.startsWith("image/") && !mime.startsWith("audio/")) return emptyList()
                    types.add(mime)
                }
            return types.toList()
        }
    }
}
