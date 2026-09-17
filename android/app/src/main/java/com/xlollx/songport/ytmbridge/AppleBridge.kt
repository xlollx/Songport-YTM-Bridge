package com.xlollx.songport.ytmbridge

import android.content.Context
import android.util.Base64
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Apple Music through the web player session, without an Apple Developer Program membership.
 *
 * Two tokens are needed by the MusicKit API: a developer token and a music user token. The web
 * player at music.apple.com ships Apple's own developer token inside its JavaScript, and after the
 * user signs in it keeps the music user token in the `media-user-token` cookie. The Bridge reads
 * both and hands them to Songport, which then uses its normal Apple Music code.
 */
object AppleBridge {
    val session = WebSession("apple", listOf("music.apple.com", "idmsa.apple.com", "apple.com"))
    const val HOME = "https://music.apple.com/"
    const val API = "https://api.music.apple.com"

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    fun signedIn(cookies: String?): Boolean = userToken(cookies) != null

    /** The music user token, URL-decoded when the cookie stores it encoded. */
    fun userToken(cookies: String?): String? {
        val raw = WebSession.cookieValue(cookies, "media-user-token") ?: return null
        return if (raw.contains('%')) runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw) else raw
    }

    /** Apple's web developer token, scraped from the player's JavaScript and cached until it expires. */
    @Synchronized
    fun developerToken(ctx: Context): String {
        session.get(ctx, "devToken")?.let { tok ->
            val exp = session.get(ctx, "devTokenExp")?.toLongOrNull() ?: 0
            if (exp - 3_600_000 > System.currentTimeMillis()) return tok
        }
        val html = get(HOME)
        val scripts = Regex("""src="(/assets/index[^"]*\.js)"""").findAll(html).map { it.groupValues[1] }.toList()
        if (scripts.isEmpty()) throw BridgeException("Apple Music: player script not found")
        for (path in scripts) {
            val js = get("https://music.apple.com$path")
            val jwt = Regex("""(eyJhbGci[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)""").find(js)?.groupValues?.get(1) ?: continue
            val exp = jwtExpiry(jwt) ?: (System.currentTimeMillis() + 7L * 24 * 3_600_000)
            session.put(ctx, "devToken", jwt)
            session.put(ctx, "devTokenExp", exp.toString())
            return jwt
        }
        throw BridgeException("Apple Music: developer token not found in the player script")
    }

    /** Storefront of the signed-in account (it, de, us...), fetched once. */
    fun storefront(ctx: Context): String? {
        session.get(ctx, "storefront")?.let { return it }
        val user = userToken(session.cookies(ctx)) ?: return null
        val req = Request.Builder().url("$API/v1/me/storefront")
            .header("Authorization", "Bearer ${developerToken(ctx)}")
            .header("Music-User-Token", user)
            .header("Origin", "https://music.apple.com")
            .header("User-Agent", UA)
            .build()
        http.newCall(req).execute().use { resp ->
            val j = parseJson(resp.body?.string() ?: "") as? JsonObject ?: return null
            val sf = j["data"][0]["id"].str ?: return null
            session.put(ctx, "storefront", sf)
            return sf
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "*/*").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw BridgeException("Apple Music ${resp.code} for $url")
            return resp.body?.string() ?: ""
        }
    }

    private fun jwtExpiry(jwt: String): Long? = runCatching {
        val payload = jwt.split('.')[1]
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        (parseJson(json) as? JsonObject)?.get("exp").long?.times(1000)
    }.getOrNull()
}
