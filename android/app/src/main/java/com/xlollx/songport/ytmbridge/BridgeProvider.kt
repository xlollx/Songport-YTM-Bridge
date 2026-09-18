package com.xlollx.songport.ytmbridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

/**
 * The interface Songport talks to, through `ContentResolver.call()`.
 *
 * Every call checks who is asking: only the Songport package, signed with one of the known
 * certificates, gets an answer. Anything else receives an error and no data. The session cookies
 * themselves are never returned: callers only get playlists and tracks.
 */
class BridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return err("no context")
        if (!Allowed.callerOk(ctx, callingPackage)) return err("caller not allowed")
        return try {
            val client = YtmClient(ctx)
            when (method) {
                "status" -> Bundle().apply {
                    putBoolean("connected", Session.isConnected(ctx))
                    putString("account", Session.account(ctx))
                    putInt("version", BuildConfig.VERSION_CODE)
                }
                "disconnect" -> { Session.clear(ctx); Bundle() }
                "playlists" -> ok(json.encodeToString(client.playlists()))
                "playlistInfo" -> ok(json.encodeToString(client.playlistInfo(arg ?: return err("missing playlist id"))))
                "tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val offset = extras?.getInt("offset") ?: 0
                    val all = cachedTracks(client, id)
                    val page = all.drop(offset).take(PAGE)
                    Bundle().apply {
                        putString("json", json.encodeToString(page))
                        putInt("total", all.size)
                        if (offset + PAGE < all.size) putInt("next", offset + PAGE)
                    }
                }
                "search" -> ok(json.encodeToString(client.search(arg ?: "", extras?.getBoolean("videos") == true)))
                "stats" -> Bundle().apply { putString("stats", YtmClient.Stats.summary()) }
                "create" -> ok(json.encodeToString(client.createPlaylist(extras?.getString("name") ?: "Playlist", extras?.getString("description") ?: "")))
                "add" -> {
                    client.addTracks(arg ?: return err("missing playlist id"), extras?.getStringArray("ids")?.toList() ?: emptyList())
                    invalidate(arg)
                    Bundle()
                }
                "remove" -> {
                    val items = json.decodeFromString<List<RemoveItem>>(extras?.getString("json") ?: "[]")
                    client.removeTracks(arg ?: return err("missing playlist id"), items)
                    invalidate(arg)
                    Bundle()
                }
                // ---- Amazon Music (experimental): only what the protocol mapping covers so far.
                "amazon.status" -> Bundle().apply {
                    putBoolean("connected", AmazonSession.isConnected(ctx))
                    putString("account", AmazonSession.account(ctx))
                    putInt("version", BuildConfig.VERSION_CODE)
                }
                "amazon.disconnect" -> { AmazonSession.clear(ctx); Bundle() }
                "amazon.search" -> ok(json.encodeToString(AmazonClient(ctx).searchTracks(arg ?: "")))
                "amazon.tracks" -> ok(json.encodeToString(AmazonClient(ctx).playlistTracks(arg ?: return err("missing playlist id"))))
                "amazon.playlists" -> ok(json.encodeToString(AmazonClient(ctx).libraryPlaylists()))
                "amazon.create" -> ok(json.encodeToString(AmazonClient(ctx).createPlaylist(extras?.getString("name") ?: "Playlist")))
                "amazon.add" -> {
                    val pid = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids") ?: emptyArray()
                    val titles = extras?.getStringArray("titles") ?: emptyArray()
                    val pname = extras?.getString("playlistName") ?: ""
                    val c = AmazonClient(ctx)
                    ids.forEachIndexed { i, id -> c.addTrack(pid, pname, id, titles.getOrNull(i) ?: ""); Thread.sleep(300) }
                    Bundle()
                }
                "amazon.remove" -> {
                    val pid = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids") ?: emptyArray()
                    val entries = extras?.getStringArray("entryIds") ?: emptyArray()
                    val c = AmazonClient(ctx)
                    // Entry ids missing (e.g. old cache): look them up from the playlist itself.
                    val byTrack = if (entries.any { it.isEmpty() }) c.playlistTracks(pid).associate { it.id to (it.setVideoId ?: "") } else emptyMap()
                    ids.forEachIndexed { i, id ->
                        val entry = entries.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: byTrack[id] ?: return@forEachIndexed
                        c.removeTrack(pid, entry, id); Thread.sleep(300)
                    }
                    Bundle()
                }
                // ---- Spotify web session: Songport gets a short-lived Web API token, never the cookies.
                "spotify.status" -> Bundle().apply {
                    putBoolean("connected", SpotifyBridge.session.isConnected(ctx))
                    putString("account", SpotifyBridge.session.account(ctx))
                    putString("userId", SpotifyBridge.session.get(ctx, "userId"))
                    putInt("version", BuildConfig.VERSION_CODE)
                }
                "spotify.disconnect" -> { SpotifyBridge.session.clear(ctx); Bundle() }
                "spotify.token" -> {
                    val t = SpotifyBridge.token(ctx)
                    if (SpotifyBridge.session.get(ctx, "userId") == null) runCatching {
                        val (id, name) = SpotifyBridge.accountInfo(ctx)
                        id?.let { SpotifyBridge.session.put(ctx, "userId", it) }
                        SpotifyBridge.session.save(ctx, SpotifyBridge.session.cookies(ctx) ?: "", name)
                    }
                    Bundle().apply {
                        putString("token", t.value)
                        putLong("expiresAt", t.expiresAt)
                        putString("userId", SpotifyBridge.session.get(ctx, "userId"))
                        putString("account", SpotifyBridge.session.account(ctx))
                    }
                }
                // ---- Apple Music web session: Apple's web developer token plus the user's music user token.
                "apple.status" -> Bundle().apply {
                    putBoolean("connected", AppleBridge.session.isConnected(ctx))
                    putString("account", AppleBridge.session.account(ctx))
                    putInt("version", BuildConfig.VERSION_CODE)
                }
                "apple.disconnect" -> { AppleBridge.session.clear(ctx); Bundle() }
                "apple.tokens" -> {
                    val user = AppleBridge.userToken(AppleBridge.session.cookies(ctx)) ?: return err("not connected")
                    Bundle().apply {
                        putString("developerToken", AppleBridge.developerToken(ctx))
                        putString("userToken", user)
                        putString("storefront", runCatching { AppleBridge.storefront(ctx) }.getOrNull())
                    }
                }
                else -> err("unknown method $method")
            }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    // A playlist is fetched once and served in pages (Binder transactions are capped at ~1 MB).
    private var lastTracks: Triple<String, Long, List<TrackDto>>? = null

    @Synchronized
    private fun cachedTracks(client: YtmClient, id: String): List<TrackDto> {
        val now = System.currentTimeMillis()
        lastTracks?.let { (pid, at, list) -> if (pid == id && now - at < 120_000) return list }
        val list = client.tracks(id)
        lastTracks = Triple(id, now, list)
        return list
    }

    @Synchronized
    private fun invalidate(id: String) { if (lastTracks?.first == id) lastTracks = null }

    private fun ok(jsonText: String) = Bundle().apply { putString("json", jsonText) }
    private fun err(message: String) = Bundle().apply { putString("error", message) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val PAGE = 400
        const val AMAZON_NOT_READY = "not available yet: the Amazon Music connector is still being completed (see Capture traffic in Songport Bridge)"
    }
}

/** Who may call the provider. Package name and signing certificate must both match. */
object Allowed {
    private const val SONGPORT = "com.xlollx.songport"

    /**
     * SHA-256 of the signing certificates of official Songport builds (upper-case hex, no colons):
     * the release upload key, the debug key used by the CI builds, and, once the app is on Google
     * Play, the Play App Signing key. A fork that rebuilds Songport with its own key must also
     * rebuild the Bridge with its own list.
     */
    private val SHA256 = setOf(
        "21EADF268C72F85565961A6045BF4BE5ABAF256D680D1E0D808D93ED22D35032", // release upload key
        "8BEC806768B3789A40E741BBFCEC462C71950E32A7A1DA15665E95BB7A0AE754", // CI debug key
    )

    fun callerOk(ctx: Context, callingPackage: String?): Boolean {
        val pkg = callingPackage ?: return false
        if (pkg == ctx.packageName) return true
        if (pkg != SONGPORT) return false
        val pm = ctx.packageManager
        val signers = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } catch (e: Exception) {
            null
        } ?: return false
        return signers.any { sha256(it.toByteArray()) in SHA256 }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
}
