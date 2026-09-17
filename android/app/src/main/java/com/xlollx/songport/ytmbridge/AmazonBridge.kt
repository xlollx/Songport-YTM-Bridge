package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/**
 * The credentials of the Amazon Music web player, taken from the player itself.
 *
 * Amazon hands out an access token only to the running player: `config.json` is served anonymously
 * and the page carries an empty `accessToken`. So the Bridge loads the player in a hidden WebView
 * with the stored session and mirrors the `x-amzn-*` headers the player puts in its own API calls
 * (they travel inside the request body, under `headers`). What Songport then sends is exactly what
 * the player sends: no guessing of device types, feature flags or CSRF values, and it keeps working
 * when Amazon changes them.
 *
 * The capture is cached for twenty minutes and blocks the caller, so never call it on the main
 * thread. Nothing leaves the phone: the headers are used only for this session's own requests.
 */
object AmazonBridge {

    /** What the player uses: its API headers (preferred) or, failing that, its configuration object. */
    class Player(val headers: Map<String, String>, val config: JsonObject?, val userAgent: String?, val at: Long) {
        /** The access token lives inside the authentication header the player builds. */
        val signedIn: Boolean
            get() = (parseJson(headers["x-amzn-authentication"] ?: "{}") as? JsonObject)["accessToken"].str?.isNotBlank() == true
    }

    private const val TTL_MS = 20 * 60_000L

    @Volatile private var cached: Player? = null

    fun forget() { cached = null }

    /** The player's credentials, from cache or freshly captured. Blocking; never on the main thread. */
    @Synchronized
    fun player(ctx: Context, domain: String): Player {
        cached?.takeIf { System.currentTimeMillis() - it.at < TTL_MS && it.signedIn }?.let { return it }
        val p = capture(ctx.applicationContext, domain)
        cached = p
        return p
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun capture(app: Context, domain: String): Player {
        val latch = CountDownLatch(1)
        // Written from the WebView thread, read here: atomic holders, not plain locals.
        val headers = AtomicReference<Map<String, String>>(null)
        val config = AtomicReference<JsonObject>(null)
        val userAgent = AtomicReference<String>(null)
        var web: WebView? = null
        val main = Handler(Looper.getMainLooper())

        val sink = object {
            /** The `headers` field of a call the player just made: the whole `x-amzn-*` set. */
            @JavascriptInterface
            fun headers(json: String, ua: String?) {
                val j = parseJson(json) as? JsonObject ?: return
                val map = j.mapNotNull { (k, v) -> v.str?.let { k to it } }.toMap()
                if ((parseJson(map["x-amzn-authentication"] ?: "{}") as? JsonObject)["accessToken"].str.isNullOrBlank()) return
                headers.set(map)
                userAgent.set(ua)
                latch.countDown()
            }

            /** `window.amznMusic.appConfig`, used only when no call was seen in time. */
            @JavascriptInterface
            fun config(json: String, ua: String?) {
                val j = parseJson(json) as? JsonObject ?: return
                config.compareAndSet(null, j)
                userAgent.compareAndSet(null, ua)
            }
        }

        main.post {
            try {
                CookieManager.getInstance().apply { setAcceptCookie(true); flush() }
                val w = WebView(app)
                web = w
                w.settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                w.addJavascriptInterface(sink, "AmzBridge")
                val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (early) WebViewCompat.addDocumentStartJavaScript(w, HOOK, setOf("*"))
                w.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        if (!early) view.evaluateJavascript(HOOK, null)
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        if (!early) view.evaluateJavascript(HOOK, null)
                        view.evaluateJavascript(CONFIG, null)
                    }
                }
                w.loadUrl("https://$domain/")
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        latch.await(40, TimeUnit.SECONDS)
        main.post { runCatching { web?.stopLoading(); web?.destroy() } }

        val h = headers.get()
        val c = config.get()
        if (h == null && c == null) throw BridgeException(
            "the Amazon Music player did not start within the time allowed. Open Songport Bridge and sign in to Amazon again.",
        )
        return Player(h ?: emptyMap(), c, userAgent.get(), System.currentTimeMillis())
    }

    /**
     * Mirrors the `x-amzn-*` headers of the player's own API calls. They are not HTTP headers: the
     * player serialises them as a JSON string inside the request body, under `headers`.
     */
    private val HOOK = """
        (function(){
          if (window.__amzHdr) return; window.__amzHdr = 1;
          var match = function(u){ return /skill\.music\.a2z\.com\/api\//.test(String(u||'')); };
          var grab = function(b){
            if (b == null) return;
            try {
              if (typeof b !== 'string') b = String(b);
              var j = JSON.parse(b);
              if (!j || !j.headers) return;
              var h = (typeof j.headers === 'string') ? j.headers : JSON.stringify(j.headers);
              AmzBridge.headers(h, navigator.userAgent);
            } catch(e){}
          };
          var of = window.fetch;
          if (of) window.fetch = function(input, init){
            var url = (typeof input === 'string') ? input : (input && input.url) || '';
            if (match(url) && init && init.body) grab(init.body);
            return of.apply(this, arguments);
          };
          var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(m, u){ this.__u = u; return oo.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function(b){ if (match(this.__u)) grab(b); return os.apply(this, arguments); };
        })();
    """.trimIndent()

    /** The configuration object, retried while the single page application boots. */
    private val CONFIG = """
        (function(){
          var tries = 0;
          var look = function(){
            try {
              var c = window.amznMusic && window.amznMusic.appConfig;
              if (c) { AmzBridge.config(JSON.stringify(c), navigator.userAgent); return; }
            } catch(e){}
            if (++tries < 20) setTimeout(look, 1000);
          };
          look();
        })();
    """.trimIndent()
}
