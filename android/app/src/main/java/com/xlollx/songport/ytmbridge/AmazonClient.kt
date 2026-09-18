package com.xlollx.songport.ytmbridge

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Talks to the endpoints the Amazon Music web player uses ("skyfire" templates on
 * *.web.skill.music.a2z.com/api/<method>). Like the YouTube Music client this is not a public API.
 *
 * Credentials come from [AmazonBridge], which runs the real player in a hidden WebView and mirrors
 * the `x-amzn-*` headers it builds for its own calls: Amazon issues an access token only to the
 * running player, so neither `config.json` nor the served page carries one. Those headers travel
 * inside the JSON body, under `headers`, not as HTTP headers.
 *
 * Methods and payloads were taken from traffic captured with the app's own "Capture traffic"
 * screen: showLibraryPlaylists, showLibraryPlaylist, createPlaylist, addTrackToPlaylist,
 * removeTrackFromPlaylist, searchCatalogTracks.
 */
class AmazonClient(private val ctx: Context) {

    /**
     * The player's configuration. Two sources carry it and name the fields differently: the page of
     * the signed-in player (`amznMusic.appConfig = {...}`, the only one that carries the customer's
     * access token) and `/config.json` (served anonymously, enough to browse the catalogue but
     * without a token). Fields are therefore looked up under every name seen so far, and
     * [sessionCookie] fills the session id from the `session-id` cookie, which is where the web
     * player itself takes it from.
     */
    class Config(val raw: JsonObject, private val sessionCookie: String? = null) {
        val accessToken get() = (raw["accessToken"].str ?: raw["tokens"]["accessToken"].str ?: raw["access_token"].str).orEmpty()
        val deviceId get() = (raw["deviceId"].str ?: raw["deviceID"].str).orEmpty()
        val sessionId get() = (raw["sessionId"].str ?: raw["sessionID"].str ?: sessionCookie).orEmpty()
        val version get() = (raw["version"].str ?: raw["clientVersion"].str ?: raw["serverInfo"]["version"].str).orEmpty()
        val csrfToken get() = (raw["csrf"]["token"].str ?: raw["CSRFTokenConfig"]["csrf_token"].str).orEmpty()
        val csrfTs get() = (raw["csrf"]["ts"] ?: raw["CSRFTokenConfig"]["csrf_ts"]).asText()
        val csrfRnd get() = (raw["csrf"]["rnd"] ?: raw["CSRFTokenConfig"]["csrf_rnd"]).asText()
        val customerId get() = raw["customerId"].str
        val customerName get() = raw["customerName"].str ?: raw["displayName"].str ?: raw["customerDisplayName"].str
            ?: raw["customer"]["name"].str ?: raw["userName"].str
        /** The player hands out an access token only to a signed-in session. */
        val signedIn get() = accessToken.isNotBlank()
        /** Names of the fields found, for the connection test: never a value, only what is there. */
        val fields: List<String> get() = raw.keys.sorted()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private fun domain(): String = AmazonSession.domain(ctx) ?: throw BridgeException("not connected")
    private fun cookies(): String = AmazonSession.cookies(ctx) ?: throw BridgeException("not connected")

    /**
     * The player configuration for the stored session. The page of the signed-in player is the only
     * source that carries the access token, so it comes first; `/config.json` is the fallback and is
     * enough for the anonymous parts. Cached for ten minutes.
     */
    fun config(domain: String = domain(), cookies: String = cookies()): Config {
        cachedConfig?.let { (at, c) -> if (System.currentTimeMillis() - at < 600_000 && c.signedIn) return c }
        val page = runCatching { pageConfig(domain, cookies) }.getOrNull()
        val c = page?.takeIf { it.signedIn }
            ?: runCatching { jsonConfig(domain, cookies) }.getOrNull()?.takeIf { it.signedIn }
            ?: page
            ?: jsonConfig(domain, cookies)
        cachedConfig = System.currentTimeMillis() to c
        return c
    }

    /** Display name of the signed-in customer, best effort: the player's configuration knows it. */
    fun accountName(): String? =
        runCatching { AmazonBridge.player(ctx, domain()).config?.let { Config(it, sessionCookie(cookies())).customerName } }.getOrNull()
            ?: runCatching { config().customerName }.getOrNull()

    /** `amznMusic.appConfig = {...};` inlined in the page the player is served, read with the session. */
    private fun pageConfig(domain: String, cookies: String): Config? {
        val html = get("https://$domain/", cookies, "text/html,application/xhtml+xml")
        // The name also appears in the player's own code: take the assignment, not every mention.
        for (m in Regex(Regex.escape(APP_CONFIG) + """\s*=\s*\{""").findAll(html)) {
            val obj = braced(html, m.range.last) ?: continue
            val j = runCatching { parseJson(obj) }.getOrNull() as? JsonObject ?: continue
            return Config(j, sessionCookie(cookies))
        }
        return null
    }

    private fun jsonConfig(domain: String, cookies: String): Config {
        val text = get("https://$domain/config.json", cookies, "*/*")
        val j = parseJson(text) as? JsonObject ?: throw BridgeException("Amazon Music config: unexpected response")
        return Config(j, sessionCookie(cookies))
    }

    private fun get(url: String, cookies: String, accept: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://${url.substringAfter("https://").substringBefore('/')}/")
            .build()
        val (code, text) = send(req)
        val what = url.substringAfterLast('/').ifBlank { "player page" }
        if (code !in 200..299) throw BridgeException("Amazon Music $code on $what: ${text.take(160)}")
        return text
    }

    /** The whole `{...}` starting at [from], counting braces outside of strings. */
    private fun braced(s: String, from: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in from until s.length) {
            val c = s[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) return s.substring(from, i + 1) }
            }
        }
        return null
    }

    /** The web player sends the `session-id` cookie as `x-amzn-session-id`. */
    private fun sessionCookie(cookies: String): String? = cookies.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("session-id=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }

    /**
     * One HTTP exchange, retried on transport errors. A phone switching between Wi-Fi and mobile data
     * fails a lookup or a connection for a moment ("Unable to resolve host"): that is not Amazon
     * answering, so the call is repeated after a short pause before giving up.
     */
    private fun send(req: Request): Pair<Int, String> {
        var attempt = 0
        while (true) {
            try {
                http.newCall(req).execute().use { resp -> return resp.code to (resp.body?.string() ?: "") }
            } catch (e: java.io.IOException) {
                if (attempt >= 2) throw BridgeException("Amazon Music unreachable: ${e.message ?: e.javaClass.simpleName}")
                Thread.sleep(if (attempt == 0) 2_000 else 5_000)
                attempt++
            }
        }
    }

    private fun skillEndpoint(domain: String): String =
        "https://${(DOMAINS[domain]?.region ?: "EU").lowercase()}.web.skill.music.a2z.com"

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
    fun call(method: String, params: Map<String, Any?>, pageUrl: String = ""): JsonElement =
        callUrl("${skillEndpoint(domain())}/api/$method", params, pageUrl)

    /**
     * The `x-amzn-*` set for one call: the player's own, captured from its traffic, with the values
     * that change on every request refreshed here. Falling back to a set built from the player's
     * configuration keeps things working if Amazon stops making calls we can see.
     */
    private fun apiHeaders(domain: String, pageUrl: String): Map<String, String> {
        val player = AmazonBridge.player(ctx, domain)
        val fresh = mapOf(
            "x-amzn-timestamp" to System.currentTimeMillis().toString(),
            "x-amzn-request-id" to java.util.UUID.randomUUID().toString().replace("-", "").take(13),
            "x-amzn-page-url" to pageUrl,
            "x-amzn-music-domain" to domain,
            "x-amzn-referer" to domain,
        )
        if (player.headers.isNotEmpty()) return player.headers + fresh
        val c = Config(player.config ?: JsonObject(emptyMap()), sessionCookie(cookies()))
        if (!c.signedIn) throw BridgeException(
            "the Amazon Music player did not hand out an access token: sign in to Amazon again in Songport Bridge. " +
                "Fields: " + c.fields.joinToString(", ").take(200).ifBlank { "none" },
        )
        return xAmznHeaders(c, domain, pageUrl) + fresh
    }

    /** A call to an absolute skill URL (also the ones the responses hand back for the next page). */
    private fun callUrl(url: String, params: Map<String, Any?>, pageUrl: String = ""): JsonElement {
        val domain = domain()
        val body = jsonObj(
            *params.toList().toTypedArray(),
            "userHash" to jsonObj("level" to USER_LEVEL).toString(),
            "headers" to JsonObject(apiHeaders(domain, pageUrl).mapValues { JsonPrimitive(it.value) }).toString(),
        )
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(TEXT_TYPE))
            .header("Origin", "https://$domain")
            .header("Referer", "https://$domain/")
            // The same user agent the player used when it built those headers.
            .header("User-Agent", AmazonBridge.player(ctx, domain).userAgent ?: USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        val (code, text) = send(req)
        if (code == 401 || code == 403) {
            cachedConfig = null
            AmazonBridge.forget()
            throw BridgeException("Amazon Music rejected the session ($code): sign in again in Songport Bridge")
        }
        if (code !in 200..299) throw BridgeException("Amazon Music $code: ${text.take(200)}")
        return parseJson(text)
    }

    // ------------------------------------------------------------------ library

    /** The user's playlists (library). */
    fun libraryPlaylists(): List<PlaylistDto> {
        val j = call("showLibraryPlaylists", emptyMap(), "https://${domain()}/my/playlists")
        val out = LinkedHashMap<String, PlaylistDto>()
        for (item in allItems(j)) {
            val link = item["primaryLink"]["deeplink"].str ?: continue
            val id = link.substringAfter("/my/playlists/", "").substringBefore('?').substringBefore('/')
            if (id.isEmpty()) continue
            val name = item["primaryText"]["text"].str?.takeIf { it.isNotBlank() }
                ?: item["primaryText"]["observer"]["defaultValue"]["text"].str
                ?: item["primaryText"].str ?: id
            out.putIfAbsent(id, PlaylistDto(id, name, -1))
        }
        return out.values.toList()
    }

    /** Tracks of a library playlist, following the "more" hook the player uses for long lists. */
    fun playlistTracks(id: String): List<TrackDto> {
        val domain = domain()
        var j = call("showLibraryPlaylist", mapOf("id" to id), "https://$domain/my/playlists/$id")
        val out = LinkedHashMap<String, TrackDto>()
        var pages = 0
        while (true) {
            for (t in parseItems(j)) out.putIfAbsent(t.setVideoId ?: t.id, t)
            val next = j.findAll("onEndOfWidget").flatMap { (it as? JsonArray) ?: emptyList() }
                .mapNotNull { it["url"].str }.firstOrNull { "/api/" in it } ?: break
            if (++pages > 200) break
            j = callUrl(next, emptyMap(), "https://$domain/my/playlists/$id")
            if (parseItems(j).isEmpty()) break
        }
        return out.values.toList()
    }

    /** Catalog search, songs only. Track ids are ASINs. */
    fun searchTracks(query: String): List<TrackDto> {
        val domain = domain()
        val j = call("searchCatalogTracks", mapOf("keyword" to query), "https://$domain/search/${enc(query)}/songs")
        // Amazon lists the same recording once per edition (album, single, deluxe): one row per
        // title and artist is enough, and a longer list lets the right artist show up further down.
        val seen = HashSet<String>()
        return parseItems(j).filter { seen.add(it.title.lowercase() + "\u0000" + it.artists.joinToString(",").lowercase()) }.take(25)
    }

    fun createPlaylist(name: String): PlaylistDto {
        val info = jsonObj(
            "interface" to "Web.TemplatesInterface.v1_0.Touch.PlaylistTemplateInterface.PlaylistClientInformation",
            "name" to name,
            "path" to "/my/playlists",
        ).toString()
        val j = call("createPlaylist", mapOf("playlistInfo" to info), "https://${domain()}/my/playlists")
        // The answer is a chain of UI methods; the new id sits in the addTracksToPlaylist URL it proposes.
        val id = Regex("""playlistId=([0-9a-fA-F-]{20,})""").find(j.toString())?.groupValues?.get(1)
            ?: throw BridgeException("Amazon Music: playlist not created")
        return PlaylistDto(id, name, 0)
    }

    fun addTrack(playlistId: String, playlistTitle: String, trackId: String, trackTitle: String) {
        call(
            "addTrackToPlaylist",
            mapOf(
                "isTrackInLibrary" to "false",
                "playlistId" to playlistId,
                "playlistTitle" to playlistTitle,
                "rejectDuplicate" to "true",
                "shouldReplaceAddedTrack" to "false",
                "trackId" to trackId,
                "trackTitle" to trackTitle,
                "version" to "1",
            ),
            "https://${domain()}/my/playlists/$playlistId",
        )
    }

    fun removeTrack(playlistId: String, trackEntryId: String, trackId: String) {
        call(
            "removeTrackFromPlaylist",
            mapOf("playlistId" to playlistId, "trackEntryId" to trackEntryId, "trackId" to trackId),
            "https://${domain()}/my/playlists/$playlistId",
        )
    }

    // ------------------------------------------------------------------ parsing

    private fun allItems(j: JsonElement): List<JsonElement> =
        j.findAll("items").flatMap { (it as? JsonArray) ?: emptyList() }

    /**
     * Track rows of any template: library rows carry `primaryText` as a string, `secondaryText1..3`
     * (artist, album, mm:ss), an `id` (the playlist entry, needed to remove) and a deeplink with
     * `trackAsin=`; search rows carry `primaryText.text`, `secondaryText` and a storage key
     * "album:track". Tolerant of both.
     */
    private fun parseItems(j: JsonElement): List<TrackDto> {
        val out = ArrayList<TrackDto>()
        for (item in allItems(j)) {
            val link = item["primaryLink"]["deeplink"].str ?: ""
            val fromLink = Regex("""[?&]trackAsin=([A-Z0-9]{10})""").find(link)?.groupValues?.get(1)
                ?: link.substringAfter("/tracks/", "").substringBefore('/').substringBefore('?').takeIf { it.isNotEmpty() }
            val storageKey = item["iconButton"]["observer"]["storageKey"].str ?: item.findFirst("storageKey").str
            val fromKey = storageKey?.substringAfter(':', "")?.takeIf { it.length == 10 }
            val id = fromLink ?: fromKey ?: continue
            val title = item["primaryText"].str ?: item["primaryText"]["text"].str ?: continue
            val artist = item["secondaryText1"].str ?: item["secondaryText"].str ?: item["secondaryText"]["text"].str ?: ""
            val album = item["secondaryText2"].str ?: item["imageAltText"].str ?: ""
            val dur = item["secondaryText3"].str
            val entry = item["id"].str?.takeIf { it.length > 20 }
            out += TrackDto(id, title, if (artist.isBlank()) emptyList() else listOf(artist), album, parseDuration(dur), entry)
        }
        return out
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
        /** Cache hint the player sends with every call; the token decides the actual rights. */
        private const val USER_LEVEL = "LIBRARY_MEMBER"
        private val TEXT_TYPE = "text/plain;charset=UTF-8".toMediaType()
        private const val HD = "hd-supported,uhd-supported"
        /** Where the signed-in player inlines its configuration in the page. */
        private const val APP_CONFIG = "amznMusic.appConfig"
        @Volatile private var cachedConfig: Pair<Long, Config>? = null
        fun forgetConfig() { cachedConfig = null }

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
