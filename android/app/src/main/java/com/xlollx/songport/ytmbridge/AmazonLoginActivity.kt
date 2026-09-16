package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import kotlin.concurrent.thread

/**
 * Amazon sign-in inside a WebView. Amazon redirects music.amazon.com to the account's regional site;
 * when the cookie jar of that site holds an `at-*` token and `config.json` confirms the session, the
 * cookies and the domain are stored encrypted and the activity closes.
 */
class AmazonLoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    @Volatile private var checking = false
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            override fun onPageFinished(view: WebView, url: String) {
                val host = Uri.parse(url).host ?: return
                if (done || checking || !AmazonClient.isMusicDomain(host)) return
                val cookies = CookieManager.getInstance().getCookie("https://$host") ?: return
                if (!AmazonSession.looksSignedIn(cookies)) return
                checking = true
                thread {
                    val cfg = runCatching { AmazonClient(applicationContext).config(host, cookies) }.getOrNull()
                    if (cfg != null && cfg.signedIn) {
                        done = true
                        AmazonSession.save(applicationContext, cookies, host, cfg.customerName)
                        runOnUiThread { setResult(RESULT_OK); finish() }
                    }
                    checking = false
                }
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else { setResult(RESULT_CANCELED); finish() }
        }
        web.loadUrl(LOGIN_URL)
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION = "com.xlollx.songport.ytmbridge.AMAZON_LOGIN"
        private const val LOGIN_URL = "https://music.amazon.com/"
    }
}
