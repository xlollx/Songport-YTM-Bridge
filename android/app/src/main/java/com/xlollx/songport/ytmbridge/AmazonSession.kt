package com.xlollx.songport.ytmbridge

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The Amazon Music session: cookies of the login completed in the WebView plus the regional domain
 * the account lives on (music.amazon.it, music.amazon.de, ...). Encrypted with the Android Keystore,
 * never handed to Songport.
 */
object AmazonSession {
    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences = cached ?: synchronized(this) {
        cached ?: open(ctx.applicationContext).also { cached = it }
    }

    private fun open(ctx: Context): SharedPreferences = try {
        val key = MasterKey.Builder(ctx, "amazonbridge_master").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "amazon_session", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("amazon_session_plain", Context.MODE_PRIVATE)
    }

    fun cookies(ctx: Context): String? = prefs(ctx).getString("cookies", null)
    fun domain(ctx: Context): String? = prefs(ctx).getString("domain", null)
    fun account(ctx: Context): String? = prefs(ctx).getString("account", null)
    fun isConnected(ctx: Context): Boolean = cookies(ctx) != null && domain(ctx) != null

    fun save(ctx: Context, cookies: String, domain: String, account: String?) {
        prefs(ctx).edit().putString("cookies", cookies).putString("domain", domain).putString("account", account).apply()
    }

    /** Forgets the session here and the Amazon cookies in the WebView. */
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        runCatching {
            val cm = CookieManager.getInstance()
            // Only Amazon cookies: the YouTube Music session must survive.
            AmazonClient.DOMAINS.keys.forEach { d ->
                cm.getCookie("https://$d")?.split(';')?.forEach { c ->
                    val name = c.substringBefore('=').trim()
                    if (name.isNotEmpty()) {
                        cm.setCookie("https://$d", "$name=; Max-Age=0; Domain=.${d.removePrefix("music.")}; Path=/")
                        cm.setCookie("https://$d", "$name=; Max-Age=0; Path=/")
                    }
                }
            }
            cm.flush()
        }
    }

    /** True when the cookie jar holds an Amazon authentication token (at-main, at-acbit, at-acbde, ...). */
    fun looksSignedIn(cookies: String?): Boolean =
        cookies != null && cookies.split(';').any { it.trim().startsWith("at-") && it.contains('=') && it.substringAfter('=').trim().length > 20 }
}
