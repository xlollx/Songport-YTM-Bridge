# Songport YTM Bridge

Optional companion app for [Songport](https://github.com/xlollx/Songport). It connects YouTube
Music through the same web interface that the music.youtube.com player uses, so a Songport user
does not need to create a Google Cloud project to sync YouTube Music playlists.

It is distributed **only here, as an APK**, never on Google Play. Songport itself stays on Google
Play and uses only official APIs; it simply detects this app when installed and offers it as a way
to connect YouTube Music. Without the Bridge, Songport keeps offering the official route (the
user's own Google Cloud key).

## Read this before installing

- This is **not an official Google API**. Using it is against the
  [YouTube Terms of Service](https://www.youtube.com/t/terms), which forbid accessing the service
  through anything other than the interfaces YouTube provides.
- It **can stop working at any time**: Google changes the internal responses without notice.
  When that happens, syncs fail until a new Bridge release is published.
- In the worst case Google could **restrict the Google account** you sign in with. Use an
  account you can afford to lose, and prefer the official route for anything important.
- Nothing here bypasses authentication or accesses anyone else's data: you sign in to your own
  account, on Google's own login page, and the app reads and edits your own playlists.

## What it does

| Feature | How |
|---------|-----|
| Sign in | A WebView opens accounts.google.com. When Google lands on music.youtube.com, the session cookies are stored encrypted (Android Keystore) in this app only. |
| Playlists, tracks, liked songs | `youtubei/v1/browse` with the same client identity as the web player; responses are parsed by looking for known renderer names anywhere in the tree, not by fixed paths, so small layout changes do not break it. |
| Search | `youtubei/v1/search` with the "Songs" filter. |
| Create playlist, add, remove, like | `playlist/create`, `browse/edit_playlist`, `like/like`, `like/removelike`. |
| Interface to Songport | A `ContentProvider` (`content://com.xlollx.songport.ytmbridge.provider`) answering `call()` methods: `status`, `playlists`, `playlistInfo`, `tracks` (paged), `search`, `create`, `add`, `remove`, `disconnect`. |

No quota, no Google Cloud project, no developer key.

## Security model

- The provider is exported, but **every call verifies the caller**: package name
  `com.xlollx.songport` and the SHA-256 of its signing certificate must match the list in
  `BridgeProvider.kt` (`Allowed`). Any other app gets an error and no data.
- The session cookies never leave this app. Songport receives playlists and tracks, nothing else.
- There is no server: the phone talks to Google directly.
- Signing out deletes the stored session and the WebView cookies.

A fork that rebuilds Songport with its own signing key must also rebuild the Bridge with that
key's fingerprint in `Allowed.SHA256`, otherwise the two apps will not talk to each other.

## Install

1. Download the latest APK from the [Releases](https://github.com/xlollx/Songport-YTM-Bridge/releases) page.
2. Install it (Android asks to allow installs from your browser or file manager once).
3. Open Songport → Accounts → YouTube Music → **Connect**. The Bridge opens Google's login page;
   after signing in you are back in Songport.

Updates: install the new APK over the old one. The Bridge is signed with the same key as the
Songport release builds, so updates always install over previous versions.

## Building

JDK 17 and an Android SDK with platform 36. From `android/`:

```bash
gradle assembleDebug          # APK in app/build/outputs/apk/debug/
```

GitHub Actions builds every push. With the `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`,
`UPLOAD_KEY_ALIAS` and `UPLOAD_KEY_PASSWORD` secrets it also produces the signed release APK, and a
tag `v*` publishes it as a GitHub Release.

## When it breaks

Open an issue with the Songport diagnostics report ("Share technical details" in Songport's Log
tab) and the Bridge version. The parsing lives in `YtmClient.kt`; most fixes are a renderer name or
a field that moved.

## License

GPL-3.0, see [LICENSE](LICENSE). Not affiliated with Google or YouTube. "YouTube" and
"YouTube Music" are trademarks of Google LLC, named here only to describe compatibility.
