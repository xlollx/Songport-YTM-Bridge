package com.xlollx.songport.ytmbridge

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * The YouTube Music session: the cookies of the login the user completed in the WebView, encrypted
 * with the Android Keystore. They are as sensitive as the Google password: they never leave this
 * device and are never handed to Songport, which only receives playlists and tracks.
 */
object Session {
    const val ORIGIN = "https://music.youtube.com"

    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences = cached ?: synchronized(this) {
        cached ?: open(ctx.applicationContext).also { cached = it }
    }

    private fun open(ctx: Context): SharedPreferences = try {
        val key = MasterKey.Builder(ctx, "ytmbridge_master").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "ytm_session", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("ytm_session_plain", Context.MODE_PRIVATE)
    }

    fun cookies(ctx: Context): String? = prefs(ctx).getString("cookies", null)?.takeIf { sapisid(it) != null }
    fun account(ctx: Context): String? = prefs(ctx).getString("account", null)
    fun isConnected(ctx: Context): Boolean = cookies(ctx) != null

    fun save(ctx: Context, cookies: String, account: String?) {
        prefs(ctx).edit().putString("cookies", cookies).putString("account", account).apply()
    }

    /** Forgets the session here and in the WebView, so the next login can pick another account. */
    fun clear(ctx: Context) {
        YtmWeb.reset()
        prefs(ctx).edit().clear().apply()
        runCatching { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
    }

    /** The cookie that signs requests: SAPISID, or its __Secure-3PAPISID twin. */
    fun sapisid(cookies: String): String? {
        val map = cookies.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()
        return map["SAPISID"] ?: map["__Secure-3PAPISID"]
    }

    /** Authorization header in the form the web player itself sends: SAPISIDHASH ts_sha1("ts sapisid origin"). */
    fun authorization(cookies: String): String {
        val sid = sapisid(cookies) ?: throw BridgeException("session has no SAPISID cookie")
        val ts = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1").digest("$ts $sid $ORIGIN".toByteArray())
        return "SAPISIDHASH ${ts}_${digest.joinToString("") { "%02x".format(it) }}"
    }
}

class BridgeException(message: String) : Exception(message)
