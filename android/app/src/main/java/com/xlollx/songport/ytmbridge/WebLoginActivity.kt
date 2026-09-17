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
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
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
        // A "Done" button next to the page: a service that answers something unexpected must never
        // leave the user trapped in this screen.
        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val bar = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(24, 12, 24, 12) }
        bar.addView(android.widget.TextView(this).apply {
            text = getString(R.string.login_hint)
            textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(android.widget.Button(this).apply {
            text = getString(R.string.login_done)
            setOnClickListener {
                if (!check()) android.widget.Toast.makeText(this@WebLoginActivity, R.string.login_not_signed_in, android.widget.Toast.LENGTH_LONG).show()
            }
        })
        web = WebView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(bar); root.addView(web)
        setContentView(root)
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Spotify refuses its web player to anything whose user agent says "wv" (a WebView) and
            // shows "Playback disabled - incompatible browser". A desktop identity avoids that page;
            // Apple's pages work with the default one.
            if (service == SPOTIFY) userAgentString = SpotifyBridge.USER_AGENT
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

    /** @return true when a session was captured (the screen then closes). */
    private fun check(): Boolean {
        if (done) return true
        val origin = if (service == APPLE) "https://music.apple.com" else "https://open.spotify.com"
        val cookies = CookieManager.getInstance().getCookie(origin) ?: return false
        val ok = if (service == APPLE) AppleBridge.signedIn(cookies) else SpotifyBridge.signedIn(cookies)
        if (!ok) return false
        done = true
        val sess = if (service == APPLE) AppleBridge.session else SpotifyBridge.session
        val app = applicationContext
        sess.save(app, cookies, null)
        // Close at once: the sign-in is done. The account name needs a token or an API call, which can
        // take a while, so it is fetched afterwards and shown when the screen refreshes.
        setResult(RESULT_OK)
        finish()
        thread {
            val account = runCatching {
                if (service == APPLE) AppleBridge.storefront(app)?.let { "Apple Music (${it.uppercase()})" }
                else SpotifyBridge.accountInfo(app).let { (id, name) -> id?.let { sess.put(app, "userId", it) }; name }
            }.getOrNull()
            if (account != null) sess.save(app, cookies, account)
        }
        return true
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
