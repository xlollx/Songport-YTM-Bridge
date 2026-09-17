package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import kotlin.concurrent.thread

/**
 * Shared sign-in screen for the web sessions (Spotify, Apple Music). The user signs in on the
 * service's own page; the activity polls the cookie jar of the service's domain and, as soon as the
 * session cookie is there, stores the cookies encrypted and closes. Songport starts it with the
 * actions declared in the manifest.
 */
class WebLoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    private lateinit var service: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = when (intent.action) {
            ACTION_SPOTIFY -> SPOTIFY
            ACTION_APPLE -> APPLE
            else -> intent.getStringExtra(EXTRA_SERVICE) ?: SPOTIFY
        }
        Disclaimer.require(this) { setup() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        web = WebView(this)
        setContentView(web)
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                if (scheme == "http" || scheme == "https") return false
                if (scheme == "intent") {
                    runCatching {
                        Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
                        }.let(::startActivity)
                    }
                }
                return true
            }
            override fun onPageFinished(view: WebView, url: String) { check() }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else { setResult(RESULT_CANCELED); finish() }
        }
        web.loadUrl(if (service == APPLE) AppleBridge.HOME else SpotifyBridge.LOGIN_URL)
        // Sign-in overlays (Apple ID) do not navigate: poll the cookie jar while the screen is open.
        handler.postDelayed(object : Runnable {
            override fun run() { if (!done && !isFinishing) { check(); handler.postDelayed(this, 1500) } }
        }, 1500)
    }

    private fun check() {
        if (done) return
        val origin = if (service == APPLE) "https://music.apple.com" else "https://open.spotify.com"
        val cookies = CookieManager.getInstance().getCookie(origin) ?: return
        val ok = if (service == APPLE) AppleBridge.signedIn(cookies) else SpotifyBridge.signedIn(cookies)
        if (!ok) return
        done = true
        val sess = if (service == APPLE) AppleBridge.session else SpotifyBridge.session
        sess.save(applicationContext, cookies, null)
        thread {
            val account = runCatching {
                if (service == APPLE) AppleBridge.storefront(applicationContext)?.let { "Apple Music (${it.uppercase()})" }
                else SpotifyBridge.accountInfo(applicationContext).let { (id, name) -> id?.let { sess.put(applicationContext, "userId", it) }; name }
            }.getOrNull()
            sess.save(applicationContext, cookies, account)
            runOnUiThread { setResult(RESULT_OK); finish() }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SPOTIFY = "com.xlollx.songport.ytmbridge.SPOTIFY_LOGIN"
        const val ACTION_APPLE = "com.xlollx.songport.ytmbridge.APPLE_LOGIN"
        const val EXTRA_SERVICE = "service"
        const val SPOTIFY = "spotify"
        const val APPLE = "apple"

        fun intent(ctx: android.content.Context, service: String): Intent =
            Intent(ctx, WebLoginActivity::class.java).setAction(if (service == APPLE) ACTION_APPLE else ACTION_SPOTIFY)
    }
}
