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
                "search" -> ok(json.encodeToString(client.search(arg ?: "")))
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
