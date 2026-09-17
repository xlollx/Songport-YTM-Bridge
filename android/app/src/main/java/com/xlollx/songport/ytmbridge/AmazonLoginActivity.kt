package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import kotlin.concurrent.thread

/**
 * Amazon sign-in inside a WebView. Amazon redirects music.amazon.com to the account's regional site;
 * as soon as the cookie jar of that site holds an `at-*` token the session is stored encrypted and
 * the screen closes. The account name is fetched afterwards and is not required: a web player that
 * answers something unexpected must not leave the user trapped in this screen, so there is also a
 * "Done" button that closes it with whatever session is there.
 */
class AmazonLoginActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
    private lateinit var web: WebView
    private lateinit var hintView: TextView
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var done = false
    @Volatile private var left = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Disclaimer.require(this) { setup() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 12, 24, 12) }
        val hint = TextView(this).apply {
            text = getString(R.string.login_hint)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        hintView = hint
        val doneButton = Button(this).apply {
            text = getString(R.string.login_done)
            setOnClickListener { if (done) leave() else capture(manual = true) }
        }
        bar.addView(hint); bar.addView(doneButton)
        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
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
        }
        // The player runs here, in front of the user: the surest place to see the headers it builds
        // for its own API calls, which are the only thing carrying an access token.
        val early = AmazonBridge.install(this, web)
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!early) view.evaluateJavascript(AmazonBridge.HOOK, null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!early) view.evaluateJavascript(AmazonBridge.HOOK, null)
                capture()
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else { setResult(RESULT_CANCELED); finish() }
        }
        web.loadUrl(LOGIN_URL)
        // The player is a single page app: navigation does not always report a finished page.
        handler.postDelayed(object : Runnable {
            override fun run() { if (!done && !isFinishing) { capture(); handler.postDelayed(this, 1500) } }
        }, 1500)
    }

    /** Stores the session if the cookies are there; with [manual] it also reports when they are not. */
    private fun capture(manual: Boolean = false) {
        if (done) return
        val cm = CookieManager.getInstance()
        // Amazon sends every account to its own regional site, and the sign-in itself happens on
        // amazon.<tld>, not on the music one: the domain to keep is the one whose cookie jar really
        // holds the token, starting from the site the player is showing right now.
        val current = Uri.parse(web.url ?: "").host?.takeIf { AmazonClient.isMusicDomain(it) }
        val hit = (listOfNotNull(current) + AmazonClient.DOMAINS.keys).distinct()
            .map { it to cm.getCookie("https://$it") }
            .firstOrNull { (_, ck) -> AmazonSession.looksSignedIn(ck) }
        if (hit == null) {
            if (manual) Toast.makeText(this, R.string.login_not_signed_in, Toast.LENGTH_LONG).show()
            return
        }
        val host = hit.first
        val cookies = hit.second ?: return
        done = true
        val app = applicationContext
        AmazonSession.save(app, cookies, host, null)
        AmazonClient.forgetConfig()
        // The display name comes from the player configuration; a failure here is not a failed login.
        thread {
            val name = runCatching { AmazonClient(app).config(host, cookies).customerName }.getOrNull()
            if (name != null) AmazonSession.save(app, cookies, host, name)
        }
        // Cookies alone are not enough: Amazon gives an access token only to the running player, and
        // the player only runs here. So stay a few seconds on its page until the hook has seen a call,
        // rather than closing on the cookies and failing at the first request. Done leaves at once.
        if (AmazonBridge.ready(app)) { leave(); return }
        hintView.text = getString(R.string.login_finishing)
        if (AmazonClient.isMusicDomain(Uri.parse(web.url ?: "").host)) web.reload() else web.loadUrl("https://$host/")
        val deadline = System.currentTimeMillis() + WAIT_MS
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (left || isFinishing) return
                if (AmazonBridge.ready(app) || System.currentTimeMillis() > deadline) leave()
                else handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun leave() {
        if (left) return
        left = true
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION = "com.xlollx.songport.ytmbridge.AMAZON_LOGIN"
        private const val LOGIN_URL = "https://music.amazon.com/"
        /** How long to wait for the player to make its first call once signed in. */
        private const val WAIT_MS = 25_000L
    }
}
