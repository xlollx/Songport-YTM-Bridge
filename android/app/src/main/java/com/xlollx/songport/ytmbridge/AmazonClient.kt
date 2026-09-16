package com.xlollx.songport.ytmbridge

import android.content.Context
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Talks to the endpoints the Amazon Music web player uses ("skyfire" templates on
 * *.mesk.skill.music.a2z.com). Like the YouTube Music client this is not a public API.
 *
 * Boot sequence, same as the web player: `GET https://<domain>/config.json` with the session
 * cookies returns the access token, device and session ids and the CSRF triple; every API call then
 * carries them as `x-amzn-*` headers, serialised inside the JSON body under `headers`.
 *
 * Status: the catalog search and the playlist lookup are known; the library listing and the write
 * methods (create playlist, add, remove) are being mapped from captured traffic.
 */
class AmazonClient(private val ctx: Context) {

    class Config(val raw: JsonObject) {
        val accessToken get() = raw["accessToken"].str.orEmpty()
        val deviceId get() = raw["deviceId"].str.orEmpty()
        val sessionId get() = raw["sessionId"].str.orEmpty()
        val version get() = raw["version"].str.orEmpty()
        val csrfToken get() = raw["csrf"]["token"].str.orEmpty()
        val csrfTs get() = raw["csrf"]["ts"].asText()
        val csrfRnd get() = raw["csrf"]["rnd"].asText()
        val customerId get() = raw["customerId"].str
        val customerName get() = raw["customerName"].str ?: raw["displayName"].str ?: raw["customerDisplayName"].str
        val signedIn get() = accessToken.isNotBlank() && customerId != null
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private fun domain(): String = AmazonSession.domain(ctx) ?: throw BridgeException("not connected")
    private fun cookies(): String = AmazonSession.cookies(ctx) ?: throw BridgeException("not connected")

    /** Fetches the player configuration with the session cookies. */
    fun config(domain: String = domain(), cookies: String = cookies()): Config {
        val req = Request.Builder()
            .url("https://$domain/config.json")
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://$domain/")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw BridgeException("Amazon Music config ${resp.code}: ${text.take(200)}")
            val j = parseJson(text) as? JsonObject ?: throw BridgeException("Amazon Music config: unexpected response")
            return Config(j)
        }
    }

    private fun skillEndpoint(domain: String): String = DOMAINS[domain]?.let { "https://${it.region.lowercase()}.mesk.skill.music.a2z.com" }
        ?: "https://eu.mesk.skill.music.a2z.com"

    private fun xAmznHeaders(cfg: Config, domain: String, pageUrl: String): Map<String, String> {
        val reg = DOMAINS[domain]
        return linkedMapOf(
            "x-amzn-authentication" to jsonObj("interface" to "ClientAuthenticationInterface.v1_0.ClientTokenElement", "accessToken" to cfg.accessToken).toString(),
            "x-amzn-device-model" to "WEBPLAYER",
            "x-amzn-device-width" to "1920",
            "x-amzn-device-family" to "WebPlayer",
            "x-amzn-device-id" to cfg.deviceId,
            "x-amzn-user-agent" to USER_AGENT,
            "x-amzn-session-id" to cfg.sessionId,
            "x-amzn-device-height" to "1080",
            "x-amzn-request-id" to java.util.UUID.randomUUID().toString().replace("-", "").take(13),
            "x-amzn-device-language" to (reg?.language ?: "en_US"),
            "x-amzn-currency-of-preference" to (reg?.currency ?: "USD"),
            "x-amzn-os-version" to "1.0",
            "x-amzn-application-version" to cfg.version,
            "x-amzn-device-time-zone" to TimeZone.getDefault().id,
            "x-amzn-timestamp" to System.currentTimeMillis().toString(),
            "x-amzn-csrf" to jsonObj("interface" to "CSRFInterface.v1_0.CSRFHeaderElement", "token" to cfg.csrfToken, "timestamp" to cfg.csrfTs, "rndNonce" to cfg.csrfRnd).toString(),
            "x-amzn-music-domain" to domain,
            "x-amzn-referer" to domain,
            "x-amzn-affiliate-tags" to "",
            "x-amzn-ref-marker" to "",
            "x-amzn-page-url" to pageUrl,
            "x-amzn-weblab-id-overrides" to "",
            "x-amzn-video-player-token" to "",
            "x-amzn-feature-flags" to (reg?.featureFlags ?: ""),
            "x-amzn-has-profile-id" to "",
            "x-amzn-age-band" to "",
        )
    }

    /** One call to `/api/<method>`; [params] are the method arguments (`id`, `keyword`, ...). */
    fun call(method: String, params: Map<String, Any?>, pageUrl: String = "", cfg: Config? = null): JsonElement {
        val domain = domain()
        val c = cfg ?: config()
        val body = jsonObj(
            *params.toList().toTypedArray(),
            "userHash" to jsonObj("level" to "LIBRARY_MEMBER").toString(),
            "headers" to JsonObject(xAmznHeaders(c, domain, pageUrl).mapValues { JsonPrimitive(it.value) }).toString(),
        )
        val req = Request.Builder()
            .url("${skillEndpoint(domain)}/api/$method")
            .post(body.toString().toRequestBody(TEXT_TYPE))
            .header("Origin", "https://$domain")
            .header("Referer", "https://$domain/")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 401 || resp.code == 403) throw BridgeException("Amazon Music rejected the session (${resp.code}): sign in again in Songport Bridge")
            if (!resp.isSuccessful) throw BridgeException("Amazon Music ${resp.code}: ${text.take(200)}")
            return parseJson(text)
        }
    }

    /** Catalog search, songs only. Track ids are ASINs; the storage key is "albumAsin:trackAsin". */
    fun searchTracks(query: String, cfg: Config? = null): List<TrackDto> {
        val domain = domain()
        val j = call("searchCatalogTracks", mapOf("keyword" to query), "https://$domain/search/${enc(query)}/songs", cfg)
        return parseItems(j)
    }

    /** Tracks of a playlist (library or catalog) by id. */
    fun playlistTracks(id: String, cfg: Config? = null): List<TrackDto> {
        val domain = domain()
        val j = call("showLibraryPlaylist", mapOf("id" to id), "https://$domain/user-playlists/$id", cfg)
        return parseItems(j)
    }

    /** Items of the first widgets of every template; tolerant of layout changes. */
    private fun parseItems(j: JsonElement): List<TrackDto> {
        val out = ArrayList<TrackDto>()
        for (item in j.findAll("items").flatMap { (it as? kotlinx.serialization.json.JsonArray) ?: emptyList() }) {
            val storageKey = item.findFirst("storageKey").str
            val fromKey = storageKey?.substringAfter(':', "")?.takeIf { it.isNotEmpty() }
            val fromLink = item["primaryTextLink"]["deeplink"].str?.substringAfter("/tracks/", "")?.substringBefore('/')?.takeIf { it.isNotEmpty() }
            val id = fromKey ?: fromLink ?: continue
            val title = item["primaryText"]["text"].str ?: item["primaryText"].str ?: continue
            val artist = item["secondaryText"].str ?: item["secondaryText"]["text"].str ?: ""
            val album = item["secondaryText2"].str ?: item["secondaryText2"]["text"].str
            val dur = item["secondaryText3"].str ?: item["secondaryText3"]["text"].str
            out += TrackDto(id, title, if (artist.isBlank()) emptyList() else listOf(artist), album, parseDuration(dur), null)
        }
        return out.distinctBy { it.id }
    }

    private fun parseDuration(text: String?): Long {
        if (text == null) return 0
        val parts = text.trim().split(':').mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty() || parts.size > 3) return 0
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    class Region(val region: String, val language: String, val currency: String, val featureFlags: String)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        private val TEXT_TYPE = "text/plain;charset=UTF-8".toMediaType()
        private const val HD = "hd-supported,uhd-supported"

        /** Regional web player domains and the API region each one talks to. */
        val DOMAINS: Map<String, Region> = mapOf(
            "music.amazon.com" to Region("NA", "en_US", "USD", ""),
            "music.amazon.com.mx" to Region("NA", "es_MX", "MXN", ""),
            "music.amazon.com.br" to Region("NA", "pt_BR", "BRL", ""),
            "music.amazon.ca" to Region("NA", "en_CA", "CAD", ""),
            "music.amazon.co.uk" to Region("EU", "en_GB", "GBP", HD),
            "music.amazon.de" to Region("EU", "de_DE", "EUR", HD),
            "music.amazon.fr" to Region("EU", "fr_FR", "EUR", HD),
            "music.amazon.it" to Region("EU", "it_IT", "EUR", HD),
            "music.amazon.es" to Region("EU", "es_ES", "EUR", HD),
            "music.amazon.nl" to Region("EU", "nl_NL", "EUR", HD),
            "music.amazon.se" to Region("EU", "sv_SE", "SEK", HD),
            "music.amazon.pl" to Region("EU", "pl_PL", "PLN", HD),
            "music.amazon.in" to Region("EU", "en_IN", "INR", HD),
            "music.amazon.sa" to Region("EU", "ar_SA", "SAR", ""),
            "music.amazon.ae" to Region("EU", "ar_AE", "AED", ""),
            "music.amazon.co.jp" to Region("FE", "ja_JP", "JPY", HD),
            "music.amazon.com.au" to Region("FE", "en_AU", "AUD", HD),
        )

        fun isMusicDomain(host: String?): Boolean = host != null && (host in DOMAINS || host.startsWith("music.amazon."))
    }
}

private fun JsonElement?.asText(): String = when (this) {
    is JsonPrimitive -> content
    null -> ""
    else -> toString()
}
