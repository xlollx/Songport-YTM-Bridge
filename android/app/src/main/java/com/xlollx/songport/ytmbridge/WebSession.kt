package com.xlollx.songport.ytmbridge

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * A web login kept for one service (Spotify, Apple Music): the cookie jar snapshot plus a few
 * values (account name, cached tokens). Encrypted with the Android Keystore, never handed to
 * Songport as such: Songport only receives short-lived API tokens or data.
 */
class WebSession(private val name: String, private val domains: List<String>) {
    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences = cached ?: synchronized(this) {
        cached ?: open(ctx.applicationContext).also { cached = it }
    }

    private fun open(ctx: Context): SharedPreferences = try {
        val key = MasterKey.Builder(ctx, "${name}_bridge_master").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "${name}_session", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("${name}_session_plain", Context.MODE_PRIVATE)
    }

    fun cookies(ctx: Context): String? = prefs(ctx).getString("cookies", null)
    fun account(ctx: Context): String? = prefs(ctx).getString("account", null)
    fun isConnected(ctx: Context): Boolean = cookies(ctx) != null
    fun get(ctx: Context, key: String): String? = prefs(ctx).getString("x_$key", null)
    fun put(ctx: Context, key: String, value: String?) { prefs(ctx).edit().putString("x_$key", value).apply() }

    fun save(ctx: Context, cookies: String, account: String?) {
        prefs(ctx).edit().putString("cookies", cookies).putString("account", account).apply()
    }

    /** Forgets the session here and the service's cookies in the WebView (other services keep theirs). */
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        runCatching {
            val cm = CookieManager.getInstance()
            domains.forEach { d ->
                cm.getCookie("https://$d")?.split(';')?.forEach { c ->
                    val n = c.substringBefore('=').trim()
                    if (n.isNotEmpty()) {
                        cm.setCookie("https://$d", "$n=; Max-Age=0; Path=/")
                        cm.setCookie("https://$d", "$n=; Max-Age=0; Domain=.${d.substringAfter('.')}; Path=/")
                    }
                }
            }
            cm.flush()
        }
    }

    companion object {
        fun cookieValue(cookies: String?, name: String): String? = cookies?.split(';')
            ?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }
    }
}
