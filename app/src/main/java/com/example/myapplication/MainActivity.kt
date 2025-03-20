package com.example.myapplication

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.os.Build
import android.view.WindowInsets
import android.view.ViewGroup
import android.widget.FrameLayout
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebViewDatabase
import android.webkit.WebResourceResponse
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import android.webkit.WebStorage

class MainActivity : AppCompatActivity() {
    companion object {
        private const val ERROR_BAD_GATEWAY = -2
        private const val ERROR_CONNECT = -6
    }

    private var contentHasLoaded = false
    private lateinit var webView: WebView
    private lateinit var container: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // Initialize before super to prevent flash
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        
        // Initialize WebView and container before splash screen
        container = FrameLayout(this)
        webView = WebView(this)
        initializeWebView()
        
        super.onCreate(savedInstanceState)
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !contentHasLoaded }
        
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        
        window.statusBarColor = Color.TRANSPARENT
        supportActionBar?.hide()
        
        // Add padding for safe areas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                val statusBarHeight = insets.getInsets(WindowInsets.Type.statusBars()).top
                val navigationBarHeight = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                container.setPadding(0, statusBarHeight, 0, navigationBarHeight)
                insets
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else if (isNetworkAvailable()) {
            deleteDatabase("webview.db")
            deleteDatabase("webviewCache.db")
            webView.clearCache(true)
            WebStorage.getInstance().deleteAllData()
            webView.loadUrl("https://amstrade.live/")
        }

        container.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        
        setContentView(container)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            // Show a confirmation dialog before exiting
            android.app.AlertDialog.Builder(this)
                .setMessage("Do you want to exit?")
                .setCancelable(false)
                .setPositiveButton("Yes") { _, _ ->
                    super.onBackPressed()
                }
                .setNegativeButton("No", null)
                .show()
        }
    }

    private fun initializeWebView() {
        // Clear any existing data
        WebView(applicationContext).clearCache(true)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            acceptCookie()
        }

        webView.settings.apply {
            cacheMode = WebSettings.LOAD_NO_CACHE
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadsImagesAutomatically = true
            userAgentString = System.getProperty("http.agent") // Use system default user agent
        }
        
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.setBackgroundColor(Color.WHITE)  // Changed from transparent
        webView.visibility = View.VISIBLE

        webView.webViewClient = object : WebViewClient() {
            private var failingUrl: String? = null
            private var retryAttempt = 0
            private val maxRetries = 3

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url == failingUrl) {
                    // This URL previously failed, try alternative approach
                    retryWithDifferentMethod(view, url)
                    return
                }
                view.alpha = 1f
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                val url = request.url.toString()
                if (request.isForMainFrame) {
                    failingUrl = url
                    retryAttempt++
                    if (retryAttempt <= maxRetries) {
                        retryWithDifferentMethod(view, url)
                    } else {
                        showErrorPage(view)
                    }
                }
            }

            private fun retryWithDifferentMethod(view: WebView, url: String) {
                Handler(Looper.getMainLooper()).postDelayed({
                    when (retryAttempt) {
                        1 -> {
                            // Try with cache cleared
                            view.clearCache(true)
                            view.loadUrl(url)
                        }
                        2 -> {
                            // Try with different cache mode
                            view.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                            view.loadUrl(url)
                        }
                        3 -> {
                            // Final attempt with POST method
                            view.postUrl(url, ByteArray(0))
                        }
                    }
                }, 1000L * retryAttempt)
            }

            private fun showErrorPage(view: WebView) {
                contentHasLoaded = true
                view.loadUrl("file:///android_asset/error.html")
                retryAttempt = 0
                failingUrl = null
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url != failingUrl) {
                    contentHasLoaded = true
                    retryAttempt = 0
                    failingUrl = null
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Add custom headers to avoid 502
                val newRequest = request.url.toString()
                try {
                    val connection = URL(newRequest).openConnection() as HttpURLConnection
                    connection.setRequestProperty("User-Agent", webView.settings.userAgentString)
                    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    connection.setRequestProperty("Accept-Language", "en-US,en;q=0.5")
                    connection.connect()
                    
                    return WebResourceResponse(
                        connection.contentType,
                        connection.contentEncoding,
                        connection.inputStream
                    )
                } catch (e: Exception) {
                    return null
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // Check connection and reload if needed
        if (!webView.url?.contains("amstrade.live")!! && isNetworkAvailable()) {
            webView.loadUrl("https://amstrade.live/")
        }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities != null && (
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        )
    }
}

// Add this extension function at the bottom of the file
fun WebView.preload() {
    loadUrl("about:blank")
}
