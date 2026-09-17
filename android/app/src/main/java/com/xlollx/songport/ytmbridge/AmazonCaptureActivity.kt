package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File

/**
 * Development aid for the Amazon Music connector. The web player runs in this WebView with a small
 * script that mirrors every API call it makes (URL, request body, response) into a file on this
 * phone. The user browses their library, creates a playlist, adds and removes a song, then shares
 * the file so the connector can be completed against the real protocol.
 *
 * What is written: request bodies with the `headers` field removed (it carries the access token and
 * CSRF values), responses cut at 30 kB, and no cookies at all. Any `accessToken` left in a response
 * is blanked before writing.
 */
class AmazonCaptureActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var counter: TextView
    private var count = 0

    private val file: File get() = File(filesDir, "amazon-capture.jsonl")

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        count = if (file.exists()) file.readLines().size else 0

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 8, 16, 8) }
        counter = TextView(this).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val share = Button(this).apply { text = getString(R.string.amazon_capture_share); setOnClickListener { share() } }
        val clear = Button(this).apply { text = getString(R.string.amazon_capture_clear); setOnClickListener { file.delete(); count = 0; refresh() } }
        bar.addView(counter); bar.addView(clear); bar.addView(share)
        val hint = TextView(this).apply { text = getString(R.string.amazon_capture_intro); setPadding(16, 0, 16, 8); textSize = 12f }
        web = WebView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        root.addView(bar); root.addView(hint); root.addView(web)
        setContentView(root)
        refresh()

        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.addJavascriptInterface(Sink(), "SpCapture")
        val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (early) WebViewCompat.addDocumentStartJavaScript(web, HOOK, setOf("*"))
        // Watching the real player here also refreshes the connector's own credentials.
        AmazonBridge.install(this, web)
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!early) { view.evaluateJavascript(HOOK, null); view.evaluateJavascript(AmazonBridge.HOOK, null) }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!early) { view.evaluateJavascript(HOOK, null); view.evaluateJavascript(AmazonBridge.HOOK, null) }
            }
        }
        onBackPressedDispatcher.addCallback(this) { if (web.canGoBack()) web.goBack() else finish() }
        web.loadUrl("https://" + (AmazonSession.domain(this) ?: "music.amazon.com") + "/")
    }

    private fun refresh() { counter.text = getString(R.string.amazon_capture_count, count) }

    private fun share() {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Songport Bridge: Amazon Music capture")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.amazon_capture_share)))
    }

    private inner class Sink {
        @JavascriptInterface
        fun log(line: String) {
            if (file.exists() && file.length() > MAX_BYTES) return
            val safe = TOKEN.replace(line, "\"accessToken\":\"[redacted]\"")
            synchronized(this) { file.appendText(safe.replace('\n', ' ') + "\n") }
            count++
            runOnUiThread { refresh() }
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val MAX_BYTES = 6L * 1024 * 1024
        private val TOKEN = Regex("\"accessToken\"\\s*:\\s*\"[^\"]*\"")

        // Mirrors fetch() and XMLHttpRequest calls towards the Amazon Music API into SpCapture.log.
        private val HOOK = """
            (function(){
              if (window.__spCap) return; window.__spCap = 1;
              var match = function(u){ return /skill\.music\.a2z\.com|music\.amazon\.[a-z.]+\/(?:[A-Z]{2}\/)?api\//.test(String(u||'')); };
              var send = function(o){ try { SpCapture.log(JSON.stringify(o)); } catch(e){} };
              var redact = function(b){
                if (b == null) return null;
                if (typeof b !== 'string') { try { b = String(b); } catch(e) { return '[binary]'; } }
                try { var j = JSON.parse(b); if (j && typeof j === 'object') { delete j.headers; } return JSON.stringify(j).slice(0, 20000); }
                catch(e) { return b.slice(0, 4000); }
              };
              var of = window.fetch;
              if (of) window.fetch = function(input, init){
                var url = (typeof input === 'string') ? input : (input && input.url) || '';
                var method = (init && init.method) || (input && input.method) || 'GET';
                var body = init && init.body;
                var p = of.apply(this, arguments);
                if (match(url)) p.then(function(r){
                  try { r.clone().text().then(function(t){ send({t: Date.now(), url: url, method: method, body: redact(body), status: r.status, response: t.slice(0, 30000)}); }); } catch(e){}
                });
                return p;
              };
              var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function(m, u){ this.__m = m; this.__u = u; return oo.apply(this, arguments); };
              XMLHttpRequest.prototype.send = function(b){
                var x = this;
                if (match(x.__u)) x.addEventListener('loadend', function(){
                  send({t: Date.now(), url: x.__u, method: x.__m, body: redact(b), status: x.status, response: String(x.responseText || '').slice(0, 30000)});
                });
                return os.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}
