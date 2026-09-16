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

    private fun call(endpoint: String, body: Map<String, Any?> = emptyMap(), query: String = ""): JsonElement {
        val ck = cookies()
        val payload = jsonObj(
            "context" to mapOf(
                "client" to mapOf(
                    "clientName" to "WEB_REMIX",
                    "clientVersion" to CLIENT_VERSION,
                    "hl" to "en",
                    "gl" to "US",
                    "platform" to "DESKTOP",
                ),
                "user" to mapOf("lockedSafetyMode" to false),
            ),
            *body.toList().toTypedArray(),
        )
        val req = Request.Builder()
            .url("${Session.ORIGIN}/youtubei/v1/$endpoint?alt=json&prettyPrint=false$query")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .header("Cookie", ck)
            .header("Authorization", Session.authorization(ck))
            .header("X-Origin", Session.ORIGIN)
            .header("Origin", Session.ORIGIN)
            .header("X-Goog-AuthUser", "0")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 401 || resp.code == 403) {
                throw BridgeException("YouTube Music rejected the session (${resp.code}): sign in again in Songport YTM Bridge")
            }
            if (!resp.isSuccessful) throw BridgeException("YouTube Music ${resp.code}: ${text.take(200)}")
            return parseJson(text)
        }
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

    fun search(query: String): List<TrackDto> {
        if (query.isBlank()) return emptyList()
        val resp = call("search", mapOf("query" to query, "params" to SONGS_FILTER))
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
        const val CLIENT_VERSION = "1.20250901.01.00"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        /** Search filter "Songs". */
        private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val DURATION = Regex("^\\d{1,2}(:\\d{2}){1,2}$")
    }
}
