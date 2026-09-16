package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Intent
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
 * The user signs in on accounts.google.com inside a WebView, exactly as in a browser. When Google
 * lands on music.youtube.com with a session cookie, the cookies are stored encrypted and the
 * activity closes. Songport starts this screen with the action `com.xlollx.songport.ytmbridge.LOGIN`.
 *
 * The WebView keeps its default user agent on purpose. Overriding it with a desktop string makes
 * Google reject the sign-in ("this browser or app may not be secure") because the string no longer
 * matches the client hints the WebView sends; the desktop identity is only used for API calls.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The liability notice comes first, also when Songport starts this screen directly.
        Disclaimer.require(this) { setup() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        web = WebView(this)
        setContentView(web)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
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
                // Google may offer "open in app" links (intent://). Hand them to the system instead of
                // letting the WebView fail on them.
                if (scheme == "intent") {
                    runCatching {
                        Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            component = null
                            selector = null
                        }.let(::startActivity)
                    }
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (done || !url.startsWith(Session.ORIGIN)) return
                pollCookies(attemptsLeft = 20)
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else { setResult(RESULT_CANCELED); finish() }
        }
        web.loadUrl(LOGIN_URL)
    }

    /** Cookies can land a moment after the page reports finished: check a few times before giving up. */
    private fun pollCookies(attemptsLeft: Int) {
        if (done) return
        val cookies = CookieManager.getInstance().getCookie(Session.ORIGIN)
        if (cookies != null && Session.sapisid(cookies) != null) {
            done = true
            finishWith(cookies)
            return
        }
        if (attemptsLeft > 0) handler.postDelayed({ pollCookies(attemptsLeft - 1) }, 500)
    }

    private fun finishWith(cookies: String) {
        Session.save(this, cookies, null)
        thread {
            val name = runCatching { YtmClient(applicationContext).accountName() }.getOrNull()
            Session.save(applicationContext, cookies, name)
            runOnUiThread {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION = "com.xlollx.songport.ytmbridge.LOGIN"
        private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    }
}
