package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import kotlin.concurrent.thread

/**
 * The user signs in on accounts.google.com inside a WebView, exactly as in a browser. When Google
 * lands on music.youtube.com with a session cookie, the cookies are stored encrypted and the
 * activity closes. Songport starts this screen with the action `com.xlollx.songport.ytmbridge.LOGIN`.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = YtmClient.USER_AGENT
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (done || !url.startsWith(Session.ORIGIN)) return
                val cookies = CookieManager.getInstance().getCookie(Session.ORIGIN) ?: return
                if (Session.sapisid(cookies) == null) return
                done = true
                finishWith(cookies)
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else { setResult(RESULT_CANCELED); finish() }
        }
        web.loadUrl(LOGIN_URL)
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
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION = "com.xlollx.songport.ytmbridge.LOGIN"
        private const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=" +
                "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
    }
}
