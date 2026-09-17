package com.xlollx.songport.ytmbridge

import android.content.Context
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Talks to the same endpoints the music.youtube.com web player uses (`youtubei/v1/...`).
 *
 * This is not a public API: Google can change the response shapes at any time. Parsing therefore
 * never relies on exact paths; it looks for well-known renderer names anywhere in the tree and
 * degrades gracefully (a track without a video id is skipped, never guessed).
 */
class YtmClient(private val ctx: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ transport

    private fun cookies(): String = Session.cookies(ctx) ?: throw BridgeException("not connected")

    /** Thrown on 403/429: the service is refusing requests for now, not a broken session. */
    class Throttled(message: String) : Exception(message)

    /**
     * One innertube call. With [auth] the request carries the session (cookies, SAPISIDHASH); without
     * it the request is anonymous, exactly like a visitor browsing music.youtube.com without signing
     * in. Anonymous calls are used for catalogue searches: they do not count against the signed-in
     * account, whose quota is what triggers the frequent 403s during a long sync.
     */
    private fun call(endpoint: String, body: Map<String, Any?> = emptyMap(), query: String = "", auth: Boolean = true): JsonElement {
        val ck = if (auth) cookies() else null
        val v = visitor(ck)
        val payload = jsonObj(
            "context" to mapOf(
                "client" to buildMap<String, Any?> {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", v.clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                    put("platform", "DESKTOP")
                    v.visitorData?.let { put("visitorData", it) }
                },
                "user" to mapOf("lockedSafetyMode" to false),
            ),
            *body.toList().toTypedArray(),
        )
        val req = Request.Builder()
            .url("${Session.ORIGIN}/youtubei/v1/$endpoint?alt=json&prettyPrint=false$query")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .apply {
                if (ck != null) {
                    header("Cookie", ck)
                    header("Authorization", Session.authorization(ck))
                    header("X-Goog-AuthUser", "0")
                } else {
                    // Consent cookie only, as a visitor who dismissed the cookie banner.
                    header("Cookie", ANON_COOKIES)
                }
            }
            .header("X-Origin", Session.ORIGIN)
            .header("Origin", Session.ORIGIN)
            .header("Referer", "${Session.ORIGIN}/")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", v.clientVersion)
            .apply { v.visitorData?.let { header("X-Goog-Visitor-Id", it) } }
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        // The web interface throttles bursts (403/429): pace the calls, slow down further after each
        // refusal, and retry once after a short pause. Longer waits are Songport's job, which shows
        // them to the user as a countdown. A 401 is a dead session, no point retrying.
        var attempt = 0
        val lane = if (auth) SESSION_LANE else ANON_LANE
        while (true) {
            lane.pace()
            val t0 = System.currentTimeMillis()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                Stats.record(auth, resp.code, System.currentTimeMillis() - t0, text)
                when {
                    resp.code == 401 -> throw BridgeException("YouTube Music rejected the session (401): sign in again in Songport Bridge")
                    resp.code == 403 || resp.code == 429 -> {
                        lane.slowDown()
                        // A refusal can also mean a stale visitor id: refresh it before the retry.
                        invalidateVisitor(auth)
                        // A signed-in call is retried once after a pause; an anonymous one is not, its
                        // caller falls back to the session at once instead of paying the pause per search.
                        if (auth && attempt < 1) { Thread.sleep(3_000); attempt++ }
                        else {
                            val reason = Regex(""""message"\s*:\s*"([^"]{1,160})"""").find(text)?.groupValues?.get(1)
                                ?: text.replace(Regex("\\s+"), " ").trim().take(120).ifBlank { null }
                            val who = if (auth) "signed-in" else "anonymous"
                            throw Throttled(
                                "YouTube Music refused the $who request (${resp.code}): too many requests" +
                                    (reason?.let { " [$it]" } ?: "") +
                                    ". Songport will wait and retry; if it persists, sign in again in Songport Bridge",
                            )
                        }
                    }
                    !resp.isSuccessful -> throw BridgeException("YouTube Music ${resp.code}: ${text.take(200)}")
                    else -> { lane.speedUp(); return parseJson(text) }
                }
            }
        }
    }

    private data class Visitor(val visitorData: String?, val clientVersion: String, val at: Long)

    /**
     * Visitor id and client version of the real web player, read from the music.youtube.com page
     * with the session cookies (as the player itself does). Requests that carry them look like the
     * player's own and are refused far less often. Cached for an hour.
     */
    private fun visitor(ck: String?): Visitor {
        val cached = if (ck != null) cachedVisitor else cachedAnonVisitor
        cached?.takeIf { System.currentTimeMillis() - it.at < 3_600_000 }?.let { return it }
        val fresh = runCatching {
            val req = Request.Builder().url("${Session.ORIGIN}/").header("Cookie", ck ?: ANON_COOKIES).header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9").build()
            http.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                val vd = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                val ver = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                Visitor(vd, ver ?: CLIENT_VERSION, System.currentTimeMillis())
            }
        }.getOrElse { Visitor(null, CLIENT_VERSION, System.currentTimeMillis()) }
            // In Europe an anonymous page is a consent screen without visitor data: ask the endpoint
            // the player itself uses to be assigned one.
            .let { if (ck == null && it.visitorData == null) it.copy(visitorData = anonymousVisitorId(it.clientVersion)) else it }
        if (ck != null) cachedVisitor = fresh else cachedAnonVisitor = fresh
        return fresh
    }

    private fun anonymousVisitorId(clientVersion: String): String? = runCatching {
        val body = jsonObj("context" to mapOf("client" to mapOf("clientName" to "WEB_REMIX", "clientVersion" to clientVersion, "hl" to "en", "gl" to "US")))
        val req = Request.Builder().url("${Session.ORIGIN}/youtubei/v1/visitor_id?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("Cookie", ANON_COOKIES).header("User-Agent", USER_AGENT).header("Origin", Session.ORIGIN)
            .header("X-YouTube-Client-Name", "67").header("X-YouTube-Client-Version", clientVersion).build()
        http.newCall(req).execute().use { resp -> parseJson(resp.body?.string() ?: "")["responseContext"]["visitorData"].str }
    }.getOrNull()

    private fun invalidateVisitor(auth: Boolean) { if (auth) cachedVisitor = null else cachedAnonVisitor = null }

    /**
     * Adaptive pacing shared by the whole process, one lane per kind of request: anonymous searches
     * are limited per address and tolerate a brisk rhythm, signed-in calls count against the account
     * and are kept gentler. Each lane slows down after every refusal and speeds up again slowly after
     * a run of successes, never below its floor.
     */
    private class Lane(private val floorMs: Long, private val ceilingMs: Long) {
        private var lastCall = 0L
        private var paceMs = floorMs
        private var successes = 0
        val current: Long get() = paceMs

        fun pace() {
            synchronized(this) {
                val wait = lastCall + paceMs - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                lastCall = System.currentTimeMillis()
            }
        }

        fun slowDown() { synchronized(this) { paceMs = (paceMs + 500).coerceAtMost(ceilingMs); successes = 0 } }

        fun speedUp() {
            synchronized(this) { if (++successes >= 10) { successes = 0; paceMs = (paceMs - 500).coerceAtLeast(floorMs) } }
        }
    }

    /** Counters for the diagnostics report: what the two routes are doing, nothing about content. */
    object Stats {
        private var calls = 0; private var ok = 0; private var refusedAnon = 0; private var refusedAuth = 0
        private var okMs = 0L; private var slowestMs = 0L
        private var lastAnon: String? = null; private var lastAuth: String? = null

        @Synchronized fun record(auth: Boolean, code: Int, ms: Long, body: String) {
            calls++
            if (code in 200..299) { ok++; okMs += ms; if (ms > slowestMs) slowestMs = ms }
            if (code == 403 || code == 429) {
                // The service's own words, trimmed: they tell a rate limit from a rejected request.
                val why = Regex(""""message"\s*:\s*"([^"]{1,120})"""").find(body)?.groupValues?.get(1)
                    ?: body.replace(Regex("\\s+"), " ").trim().take(100).ifBlank { "(empty body)" }
                if (auth) { refusedAuth++; lastAuth = "$code $why" } else { refusedAnon++; lastAnon = "$code $why" }
            }
        }

        @Synchronized fun summary(): String =
            "calls $calls, ok $ok, avg ok ${if (ok == 0) 0 else okMs / ok} ms, slowest $slowestMs ms, " +
                "refused anonymous $refusedAnon" + (lastAnon?.let { " (last: $it)" } ?: "") +
                ", refused signed-in $refusedAuth" + (lastAuth?.let { " (last: $it)" } ?: "") +
                ", pace anonymous ${ANON_LANE.current} ms, pace signed-in ${SESSION_LANE.current} ms" +
                (if (System.currentTimeMillis() - anonRefusedAt < ANON_COOLDOWN_MS) ", anonymous route cooling down" else "")
    }

    private class Cont(val token: String, val legacy: Boolean)

    /** Continuation token inside [scope], old style (`nextContinuationData`) or new style (`continuationCommand`). */
    private fun continuationOf(scope: JsonElement?): Cont? {
        scope.findFirst("nextContinuationData")?.get("continuation").str?.let { return Cont(it, true) }
        scope.findFirst("continuationCommand")?.get("token").str?.let { return Cont(it, false) }
        return null
    }

    private fun continueBrowse(c: Cont): JsonElement =
        if (c.legacy) call("browse", query = "&ctoken=${enc(c.token)}&continuation=${enc(c.token)}&type=next")
        else call("browse", mapOf("continuation" to c.token))

    // ------------------------------------------------------------------ account

    fun accountName(): String? =
        runCatching { call("account/account_menu").findFirst("accountName").runsText() }.getOrNull()

    // ------------------------------------------------------------------ playlists

    fun playlists(): List<PlaylistDto> {
        val out = LinkedHashMap<String, PlaylistDto>()
        var resp = call("browse", mapOf("browseId" to "FEmusic_liked_playlists"))
        var pages = 0
        while (true) {
            var added = 0
            for (item in resp.findAll("musicTwoRowItemRenderer")) {
                val browseId = item["navigationEndpoint"]["browseEndpoint"]["browseId"].str ?: continue
                if (!browseId.startsWith("VL")) continue
                val id = browseId.removePrefix("VL")
                // "LM" is liked music (exposed separately by Songport), "SE" saved episodes.
                if (id == "LM" || id == "SE") continue
                val name = item["title"].runsText() ?: continue
                val count = item["subtitle"].runsText()?.let { parseCount(it) } ?: -1
                if (out.putIfAbsent(id, PlaylistDto(id, name, count)) == null) added++
            }
            val scope = resp.findFirst("gridRenderer") ?: resp.findFirst("gridContinuation") ?: resp.findFirst("onResponseReceivedActions")
            val next = continuationOf(scope) ?: break
            if (added == 0 || ++pages > 50) break
            resp = continueBrowse(next)
        }
        return out.values.toList()
    }

    fun playlistInfo(playlistId: String): PlaylistDto {
        val resp = call("browse", mapOf("browseId" to browseIdOf(playlistId)))
        val title = resp.findFirst("musicResponsiveHeaderRenderer")["title"].runsText()
            ?: resp.findFirst("musicDetailHeaderRenderer")["title"].runsText()
            ?: resp.findFirst("musicEditablePlaylistDetailHeaderRenderer").findFirst("title").runsText()
            ?: playlistId
        return PlaylistDto(playlistId, title)
    }

    // ------------------------------------------------------------------ tracks

    fun tracks(playlistId: String): List<TrackDto> {
        val out = LinkedHashMap<String, TrackDto>()
        var resp = call("browse", mapOf("browseId" to browseIdOf(playlistId)))
        var pages = 0
        while (true) {
            var added = 0
            val shelf = resp.findFirst("musicPlaylistShelfRenderer")
                ?: resp.findFirst("musicPlaylistShelfContinuation")
                ?: resp.findFirst("onResponseReceivedActions")
                ?: resp
            for (item in shelf.findAll("musicResponsiveListItemRenderer")) {
                val t = parseItem(item) ?: continue
                // The same video can appear twice in a playlist: key by set id when we have one.
                val key = t.setVideoId ?: t.id
                if (out.putIfAbsent(key, t) == null) added++
            }
            val next = continuationOf(shelf) ?: break
            if (added == 0 || ++pages > 300) break
            resp = continueBrowse(next)
        }
        return out.values.toList()
    }

    /**
     * Catalogue search. Runs anonymously first: a search does not need the account, and the account is
     * exactly what YouTube throttles after a few hundred rapid searches (the "403 every few songs" of
     * a long sync). Only when the anonymous route is refused does it fall back to the signed-in one,
     * so a sync keeps going in the worst case instead of stopping.
     */
    fun search(query: String): List<TrackDto> {
        if (query.isBlank()) return emptyList()
        val body = mapOf("query" to query, "params" to SONGS_FILTER)
        // Once the anonymous route is refused it usually stays refused for a while: searches go
        // through the session for a few minutes rather than paying a refusal on every one of them.
        val anonymousCold = System.currentTimeMillis() - anonRefusedAt < ANON_COOLDOWN_MS
        val resp = if (anonymousCold) call("search", body, auth = true) else try {
            call("search", body, auth = false)
        } catch (e: Throttled) {
            anonRefusedAt = System.currentTimeMillis()
            call("search", body, auth = true)
        }
        val seen = HashSet<String>()
        return resp.findAll("musicResponsiveListItemRenderer")
            .mapNotNull { parseItem(it) }
            .filter { seen.add(it.id) }
            .take(8)
    }

    // ------------------------------------------------------------------ writes

    fun createPlaylist(name: String, description: String): PlaylistDto {
        val resp = call("playlist/create", mapOf("title" to name, "description" to description, "privacyStatus" to "PRIVATE"))
        val id = resp["playlistId"].str ?: throw BridgeException("playlist not created: ${resp.toString().take(200)}")
        return PlaylistDto(id, name, 0)
    }

    fun addTracks(playlistId: String, videoIds: List<String>) {
        if (playlistId == LIKED) {
            videoIds.forEach { call("like/like", mapOf("target" to mapOf("videoId" to it))) }
            return
        }
        videoIds.chunked(50).forEach { chunk ->
            val resp = call(
                "browse/edit_playlist",
                mapOf("playlistId" to playlistId.removePrefix("VL"), "actions" to chunk.map { mapOf("action" to "ACTION_ADD_VIDEO", "addedVideoId" to it) }),
            )
            checkStatus(resp)
        }
    }

    fun removeTracks(playlistId: String, items: List<RemoveItem>) {
        if (playlistId == LIKED) {
            items.forEach { call("like/removelike", mapOf("target" to mapOf("videoId" to it.videoId))) }
            return
        }
        // Removal needs the set id of each entry; look it up when Songport only knows the video id.
        val missing = items.filter { it.setVideoId == null }
        val resolved = if (missing.isEmpty()) items else {
            val current = tracks(playlistId).groupBy { it.id }
            items.map { it.copy(setVideoId = it.setVideoId ?: current[it.videoId]?.firstOrNull()?.setVideoId) }
        }
        resolved.filter { it.setVideoId != null }.chunked(50).forEach { chunk ->
            val resp = call(
                "browse/edit_playlist",
                mapOf(
                    "playlistId" to playlistId.removePrefix("VL"),
                    "actions" to chunk.map { mapOf("action" to "ACTION_REMOVE_VIDEO", "removedVideoId" to it.videoId, "setVideoId" to it.setVideoId) },
                ),
            )
            checkStatus(resp)
        }
    }

    private fun checkStatus(resp: JsonElement) {
        val status = resp["status"].str ?: return
        if (status != "STATUS_SUCCEEDED") throw BridgeException("YouTube Music answered $status")
    }

    // ------------------------------------------------------------------ parsing

    private fun parseItem(r: JsonElement): TrackDto? {
        val videoId = r["playlistItemData"]["videoId"].str
            ?: r.findFirst("watchEndpoint")?.get("videoId").str
            ?: return null
        val setId = r["playlistItemData"]["playlistSetVideoId"].str
        val cols = r["flexColumns"].arr.map { it["musicResponsiveListItemFlexColumnRenderer"]["text"] }
        val title = cols.getOrNull(0).runsText() ?: return null
        val runs1 = cols.getOrNull(1)["runs"].arr
        var artists = runs1.filter { run ->
            val b = run["navigationEndpoint"]["browseEndpoint"]["browseId"].str
            b != null && (b.startsWith("UC") || b.startsWith("MPLA"))
        }.mapNotNull { it["text"].str }
        if (artists.isEmpty()) {
            // No links: take the leading text pieces, skipping separators, durations and play counts.
            artists = runs1.mapNotNull { it["text"].str }
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "•" && !DURATION.matches(it) && !it.contains("view", true) && !it.contains("play", true) }
                .take(2)
        }
        val album = (cols.getOrNull(2)["runs"].arr + runs1)
            .firstOrNull { it["navigationEndpoint"]["browseEndpoint"]["browseId"].str?.startsWith("MPREb") == true }["text"].str
            ?: ""
        val durationText = r["fixedColumns"].arr.firstOrNull()["musicResponsiveListItemFixedColumnRenderer"]["text"].runsText()
            ?: runs1.mapNotNull { it["text"].str?.trim() }.firstOrNull { DURATION.matches(it) }
        return TrackDto(videoId, title, artists, album, parseDuration(durationText), setId)
    }

    private fun parseDuration(text: String?): Long {
        if (text == null || !DURATION.matches(text.trim())) return 0
        val parts = text.trim().split(':').map { it.toLongOrNull() ?: 0 }
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
    }

    private fun parseCount(subtitle: String): Int =
        Regex("(\\d[\\d,.]*)\\s*(song|track|brani|brano|titoli|video|canzoni)", RegexOption.IGNORE_CASE)
            .find(subtitle)?.groupValues?.get(1)?.filter { it.isDigit() }?.toIntOrNull() ?: -1

    private fun browseIdOf(playlistId: String): String = when {
        playlistId == LIKED -> "VLLM"
        playlistId.startsWith("VL") -> playlistId
        else -> "VL$playlistId"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** Songport's id for "Liked songs"; maps to the LM playlist here. */
        const val LIKED = "__liked__"
        /** Signed-in calls: what a person clicking around the player would produce. */
        private val SESSION_LANE = Lane(700, 4_000)
        /** Anonymous searches: several a second are fine, Songport also runs a few in parallel. */
        private val ANON_LANE = Lane(250, 3_000)
        @Volatile private var anonRefusedAt = 0L
        private const val ANON_COOLDOWN_MS = 5 * 60_000L
        @Volatile private var cachedVisitor: Visitor? = null
        @Volatile private var cachedAnonVisitor: Visitor? = null
        /** Cookie jar of an anonymous visitor: only the consent choice, no account. */
        private const val ANON_COOKIES = "SOCS=CAI"
        const val CLIENT_VERSION = "1.20250901.01.00"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        /** Search filter "Songs". */
        private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val DURATION = Regex("^\\d{1,2}(:\\d{2}){1,2}$")
    }
}
